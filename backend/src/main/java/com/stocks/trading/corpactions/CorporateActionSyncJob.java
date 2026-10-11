package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.events.AdminAlertRaised;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.marketdata.CorporateActionSource;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code CORPORATE_ACTION_SYNC} (§14, §25: 02:30 ET daily, run key = date, catch-up LATEST_ONLY): asks the profile's
 * {@link CorporateActionSource} for the corporate actions of every held, open-order and watch-listed symbol plus the
 * benchmarks (SPY and the extra QQQ), from 7 days back to {@value #LOOK_AHEAD_DAYS} days ahead, and upserts them by
 * {@code provider_ref}.
 * <ul>
 *   <li>The look-ahead is longer than the spec's 14 days because Alpaca filters on {@code process_date}, which for a
 *       cash dividend is around the payable date: a 14-day window would find many dividends only after their
 *       ex-date.</li>
 *   <li>A new row is PENDING; its {@code ApplyCorporateActionCmd} runs at 03:45 ET on the ex-date
 *       ({@link CorporateActionApplyJob}). Types Phase 1 does not apply (mergers, spin-offs, delistings) and splits
 *       without valid rates are NEEDS_ADMIN, with an admin alert; a split reported after its ex-date that is already
 *       applied (e.g. a resolved DETECTED one) is SKIPPED. Any other split reported late stays PENDING and is applied
 *       at once below: with no holder at its ex cut-off it only adjusts prices; with holders and no trade since, it is
 *       applied as of the ex-date; only holders plus trades since put it on a corporate-action hold for the admin
 *       ({@link CorporateActionApplier#apply}).</li>
 *   <li>A row that is still PENDING takes the provider's latest data; other rows are left alone.</li>
 * </ul>
 * Finally, PENDING actions that are already overdue (their ex-date's pre-open has passed: found late, or an earlier
 * application failed) are applied at once through {@link CorporateActionApplyJob#applyOverdue}; the rest wait for 03:45.
 */
@Component
public class CorporateActionSyncJob implements ScheduledJob {

  public static final String NAME = "CORPORATE_ACTION_SYNC";
  static final LocalTime AT = LocalTime.of(2, 30);
  static final int LOOK_BACK_DAYS = 7;
  static final int LOOK_AHEAD_DAYS = 90;
  private static final Set<CorporateActionType> APPLIED_AUTOMATICALLY = EnumSet.of(CorporateActionType.NAME_CHANGE,
      CorporateActionType.FORWARD_SPLIT, CorporateActionType.REVERSE_SPLIT, CorporateActionType.CASH_DIVIDEND);

  private static final Logger log = LoggerFactory.getLogger(CorporateActionSyncJob.class);

  /** What one sync did. */
  public record Result(int received, int inserted, int refreshed, int needsAdmin, int ignored) {}

  private final CorporateActionSource source;
  private final CorporateActionStore store;
  private final PublicId publicIds;
  private final TimeSource time;
  private final MarketCalendar calendar;
  private final JdbcClient jdbc;
  private final ApplicationEventPublisher events;
  private final ObjectProvider<CorporateActionApplyJob> applyJob;
  private final String benchmarkSymbol;
  private final List<String> extraBenchmarks;

  public CorporateActionSyncJob(CorporateActionSource source, CorporateActionStore store, PublicId publicIds,
                                TimeSource time, MarketCalendar calendar, JdbcClient jdbc,
                                ApplicationEventPublisher events, ObjectProvider<CorporateActionApplyJob> applyJob,
                                @Value("${stocks.performance.benchmark-symbol:SPY}") String benchmarkSymbol,
                                @Value("${stocks.performance.extra-benchmarks:QQQ}") String extraBenchmarks) {
    this.source = source;
    this.store = store;
    this.publicIds = publicIds;
    this.time = time;
    this.calendar = calendar;
    this.jdbc = jdbc;
    this.events = events;
    this.applyJob = applyJob;
    this.benchmarkSymbol = benchmarkSymbol;
    this.extraBenchmarks = extraBenchmarks == null ? List.of() : Arrays.stream(extraBenchmarks.split(","))
        .map(String::trim).filter(x -> !x.isEmpty()).map(x -> x.toUpperCase(Locale.ROOT)).distinct().toList();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public List<JobOccurrence> occurrencesBetween(Instant fromExclusive, Instant toInclusive, MarketCalendar cal) {
    List<JobOccurrence> out = new ArrayList<>();
    for (LocalDate d = Et.date(fromExclusive); !d.isAfter(Et.date(toInclusive)); d = d.plusDays(1)) {
      Instant at = Et.at(d, AT);
      if (at.isAfter(fromExclusive) && !at.isAfter(toInclusive)) {
        out.add(new JobOccurrence(at, d.toString()));
      }
    }
    return out;
  }

  @Override
  public void run(String runKey) {
    LocalDate today = LocalDate.parse(runKey);
    Set<String> symbols = symbols();
    List<CorporateActionData> data = source.corporateActions(symbols, today.minusDays(LOOK_BACK_DAYS),
        today.plusDays(LOOK_AHEAD_DAYS));
    Result r = upsert(data);
    log.info("Corporate actions {}: {} symbols, {} received, {} new, {} refreshed, {} need the admin, {} ignored",
        runKey, symbols.size(), r.received(), r.inserted(), r.refreshed(), r.needsAdmin(), r.ignored());
    CorporateActionApplyJob apply = applyJob.getIfAvailable();
    if (apply != null) {
      apply.applyOverdue();
    }
  }

  @Override
  public CatchUpPolicy catchUp() {
    return CatchUpPolicy.LATEST_ONLY;
  }

  /**
   * Held (ACTIVE runs), open-order and watch-listed symbols, plus every benchmark (§14): the main one and the extras
   * (QQQ, §16.6), whose cash dividends feed {@code daily_mark.dividend_ex} and so their total-return chains.
   */
  Set<String> symbols() {
    Set<String> out = new TreeSet<>(jdbc.sql("""
            SELECT p.symbol FROM position p JOIN account a ON a.id = p.account_id WHERE a.status = 'ACTIVE'
            UNION SELECT symbol FROM trade_order WHERE status IN ('PENDING','WORKING','PARTIALLY_FILLED')
            UNION SELECT symbol FROM watchlist_item""")
        .query(String.class).list());
    out.add(benchmarkSymbol);
    out.addAll(extraBenchmarks);
    return out;
  }

  /** Upserts provider actions by {@code provider_ref} (see the class comment). Package-private for tests. */
  public Result upsert(List<CorporateActionData> data) {
    int inserted = 0;
    int refreshed = 0;
    int needsAdmin = 0;
    int ignored = 0;
    for (CorporateActionData d : data) {
      String symbol = CorporateActionApplier.normalize(d.symbol());
      if (d.providerRef() == null || symbol == null || d.type() == null || d.exDate() == null) {
        ignored++;
        continue;
      }
      String newSymbol = CorporateActionApplier.normalize(d.newSymbol());
      Optional<CorporateActionRow> existing = store.byProviderRef(d.providerRef());
      if (existing.isPresent()) {
        if (existing.get().status() == CorporateActionStatus.PENDING && store.refreshPending(existing.get().id(),
            symbol, d.exDate(), d.recordDate(), d.payableDate(), d.oldRate(), d.newRate(), d.cashRate(), newSymbol)) {
          refreshed++;
        }
        continue;
      }
      Instant now = time.now();
      CorporateActionStatus status = CorporateActionStatus.PENDING;
      String detail = null;
      boolean split = d.type() == CorporateActionType.FORWARD_SPLIT || d.type() == CorporateActionType.REVERSE_SPLIT;
      if (!APPLIED_AUTOMATICALLY.contains(d.type())) {
        status = CorporateActionStatus.NEEDS_ADMIN;
        detail = d.type() + " is not applied automatically in Phase 1: apply its effect by hand, then SKIP";
      } else if (split && !CorporateActionApplier.validRates(d.oldRate(), d.newRate())) {
        status = CorporateActionStatus.NEEDS_ADMIN;
        detail = "split without valid rates";
      } else if (split && !now.isBefore(ExDates.cutoff(d.exDate(), calendar))) {
        Optional<CorporateActionRow> applied = store.splitsOn(symbol, d.exDate()).stream()
            .filter(r -> r.status() == CorporateActionStatus.APPLIED).findFirst();
        if (applied.isPresent()) {
          status = CorporateActionStatus.SKIPPED;
          detail = "reported after its ex-date; superseded by " + applied.get().publicId();
        } else {
          detail = "reported after its ex-date's pre-open";   // PENDING: applyOverdue decides right below
        }
      }
      String publicId = publicIds.next(Prefix.CORPORATE_ACTION);
      store.insert(new CorporateActionStore.NewAction(publicId, d.providerRef(), symbol, d.type(), d.exDate(),
          d.recordDate(), d.payableDate(), d.oldRate(), d.newRate(), d.cashRate(), newSymbol, status,
          CorporateActionOrigin.PROVIDER, detail, now));
      inserted++;
      if (status == CorporateActionStatus.NEEDS_ADMIN) {
        needsAdmin++;
        alert(symbol, d.type(), publicId, d.exDate(), detail, now);
      }
    }
    return new Result(data.size(), inserted, refreshed, needsAdmin, ignored);
  }

  private void alert(String symbol, CorporateActionType type, String publicId, LocalDate exDate, String why,
                     Instant now) {
    try {
      events.publishEvent(new AdminAlertRaised("WARN", "Corporate action needs review: " + symbol + " " + type,
          publicId + " (ex-date " + exDate + "): " + why, now));
    } catch (RuntimeException e) {
      log.warn("Admin alert for {} failed: {}", publicId, e.toString());
    }
  }
}
