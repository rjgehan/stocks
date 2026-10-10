package com.stocks.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.money.Money;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto.HistoryFlowDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto.HistoryPointDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto.HistorySummaryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto.PublicHistoryPointDto;
import com.stocks.portfolio.IntradaySampler.Sample;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.PeriodResolver.Window;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import com.stocks.portfolio.RunAnchors.Anchor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Equity curves (§18.2 {@code GET /account/history}, {@code GET /players/{handle}/history}).
 * <ul>
 *   <li><b>Account</b> (run-relative, §16.3): the anchor (the run's EOD of the range's anchor day, or its opening),
 *       then the run's EOD snapshots ({@code 1D}: its 5-minute samples), then a live point. {@code pnl} of a point is
 *       {@code (E − NC) − (E − NC)_anchor}; the summary return is {@code U_now / U_anchor − 1}.</li>
 *   <li><b>Public</b> (per player, across runs): unit values, plus equity unless {@code HIDDEN}.</li>
 * </ul>
 */
@Component
public class PortfolioHistoryService {

  private final PortfolioRepository repository;
  private final EodSnapshotRepository snapshots;
  private final RunAnchors anchors;
  private final PeriodResolver periods;
  private final PnlCalendar calendar;
  private final ValuationService valuation;
  private final IntradaySampler sampler;
  private final BenchmarkService benchmark;
  private final TimeSource time;

  public PortfolioHistoryService(PortfolioRepository repository, EodSnapshotRepository snapshots, RunAnchors anchors,
                                 PeriodResolver periods, PnlCalendar calendar, ValuationService valuation,
                                 IntradaySampler sampler, BenchmarkService benchmark, TimeSource time) {
    this.repository = repository;
    this.snapshots = snapshots;
    this.anchors = anchors;
    this.periods = periods;
    this.calendar = calendar;
    this.valuation = valuation;
    this.sampler = sampler;
    this.benchmark = benchmark;
    this.time = time;
  }

  private record End(Instant at, BigDecimal equity, BigDecimal netContributions, BigDecimal unitValue,
                     int unitSeries) {}

  public PortfolioHistoryDto history(long accountId, HistoryRange range) {
    AccountRow run = repository.account(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + accountId + " does not exist."));
    HistoryRange r = range == null ? HistoryRange.M1 : range;
    Instant now = time.now();
    List<TransferRow> transfers = repository.appliedTransfers(run.id());
    Window window = periods.window(r, calendar.currentPnlDay(now));
    Anchor anchor = window.sinceOpening() ? RunAnchors.opening(run, transfers) : anchors.forRun(run,
        window.anchorDate());
    End end = end(run);
    BigDecimal base = anchor.pnlBase();
    List<HistoryPointDto> points = new ArrayList<>();
    List<UnitPoint> units = new ArrayList<>();
    BigDecimal benchStart = anchor.opening() ? openingBenchmark(anchor, transfers)
        : benchmark.indexAtEod(anchor.date()).orElse(null);
    points.add(new HistoryPointDto(anchor.at(), anchor.equity(), anchor.netContributions(), Money.ZERO,
        anchor.unitValue(), benchStart));
    units.add(anchor.unitPoint());
    if (r == HistoryRange.D1) {
      for (Sample s : sampler.samples(run.id(), anchor.at(), end.at())) {
        BigDecimal nc = netContributionsAt(transfers, s.sampledAt());
        points.add(new HistoryPointDto(s.sampledAt(), s.equity(), nc, s.equity().subtract(nc).subtract(base),
            s.unitValue(), null));
        units.add(new UnitPoint(s.unitValue(), s.unitSeries()));
      }
    } else {
      for (EodSnapshot s : snapshots.series(List.of(run.id()), Et.date(anchor.at()).minusDays(1), Et.date(end.at()))) {
        if (!s.asOf().isAfter(anchor.at()) || s.asOf().isAfter(end.at())) {
          continue;
        }
        points.add(new HistoryPointDto(s.asOf(), s.equity(), s.netContributions(), s.pnlSinceOpen().subtract(base),
            s.unitValue(), benchmark.indexAtEod(s.tradeDate()).orElse(null)));
        units.add(new UnitPoint(s.unitValue(), s.unitSeries()));
      }
    }
    BigDecimal benchEnd = run.active() ? benchmark.liveIndex().orElse(null) : null;
    BigDecimal pnl = end.equity().subtract(end.netContributions()).subtract(base);
    points.add(new HistoryPointDto(end.at(), end.equity(), end.netContributions(), pnl, end.unitValue(), benchEnd));
    UnitPoint endPoint = new UnitPoint(end.unitValue(), end.unitSeries());
    units.add(endPoint);
    List<HistoryFlowDto> flows = new ArrayList<>();
    for (TransferRow t : transfers) {
      boolean after = anchor.opening() ? !Long.valueOf(t.id()).equals(anchor.openingTransferId())
          : t.effectiveAt().isAfter(anchor.at());
      if (after && !t.effectiveAt().isAfter(end.at())) {
        flows.add(new HistoryFlowDto(t.effectiveAt(), t.type(), t.amount(), t.memo()));
      }
    }
    BigDecimal ret = MetricsCalculator.twr(anchor.unitPoint(), endPoint);
    HistorySummaryDto summary = new HistorySummaryDto(ret, pnl, MetricsCalculator.excess(ret, benchStart, benchEnd),
        MetricsCalculator.maxDrawdown(units));
    return new PortfolioHistoryDto(r, points, flows, summary);
  }

  /** Unit values (and equity unless hidden) of a player across runs. */
  public PublicHistoryDto publicHistory(PlayerRow player, HistoryRange range, boolean hideEquity) {
    HistoryRange r = range == null ? HistoryRange.M1 : range;
    List<AccountRow> runs = repository.runsOfPlayer(player.id());
    if (runs.isEmpty()) {
      return new PublicHistoryDto(r, List.of());
    }
    Instant now = time.now();
    Window window = periods.window(r, calendar.currentPnlDay(now));
    Anchor anchor = null;
    if (!window.sinceOpening()) {
      for (AccountRow run : runs) {
        if (run.openAt(window.anchorAt())) {
          anchor = anchors.forRun(run, window.anchorDate());
        }
      }
    }
    if (anchor == null) {                                // since the first opening, or opened after the anchor
      AccountRow first = window.sinceOpening() ? runs.get(0)
          : runs.stream().filter(a -> a.openedAt().isAfter(window.anchorAt())).findFirst().orElse(runs.get(0));
      anchor = RunAnchors.opening(first, repository.appliedTransfers(first.id()));
    }
    List<PublicHistoryPointDto> points = new ArrayList<>();
    points.add(point(anchor.at(), anchor.unitValue(), anchor.equity(), hideEquity));
    Optional<AccountRow> active = runs.stream().filter(AccountRow::active).findFirst();
    if (r == HistoryRange.D1) {
      if (active.isPresent()) {
        for (Sample s : sampler.samples(active.get().id(), anchor.at(), now)) {
          points.add(point(s.sampledAt(), s.unitValue(), s.equity(), hideEquity));
        }
      }
    } else {
      TreeMap<LocalDate, EodSnapshot> byDate = new TreeMap<>();
      List<Long> ids = runs.stream().map(AccountRow::id).toList();
      for (EodSnapshot s : snapshots.series(ids, Et.date(anchor.at()).minusDays(1), Et.date(now))) {
        if (s.asOf().isAfter(anchor.at())) {
          byDate.merge(s.tradeDate(), s, (x, y) -> y.computedAt().isAfter(x.computedAt()) ? y : x);
        }
      }
      byDate.values().forEach(s -> points.add(point(s.asOf(), s.unitValue(), s.equity(), hideEquity)));
    }
    if (active.isPresent()) {
      Valuation v = valuation.valueNow(active.get().id());
      points.add(point(v.asOf(), v.unitValue(), v.equity(), hideEquity));
    }
    return new PublicHistoryDto(r, points);
  }

  private static PublicHistoryPointDto point(Instant t, BigDecimal unitValue, BigDecimal equity, boolean hide) {
    return new PublicHistoryPointDto(t, unitValue, hide ? null : equity);
  }

  private End end(AccountRow run) {
    if (!run.active() && run.finalEquity() != null && run.finalUnitValue() != null) {
      return new End(run.closedAt() != null ? run.closedAt() : time.now(), run.finalEquity(), run.netContributions(),
          run.finalUnitValue(), run.unitSeries());
    }
    Valuation v = valuation.valueNow(run.id());
    return new End(v.asOf(), v.equity(), v.netContributions(), v.unitValue(), v.unitSeries());
  }

  /** B at a run's opening, from the SPY mark recorded on its INITIAL transfer (§16.6). */
  private BigDecimal openingBenchmark(Anchor anchor, List<TransferRow> transfers) {
    for (TransferRow t : transfers) {
      if (Long.valueOf(t.id()).equals(anchor.openingTransferId())) {
        return benchmark.indexAt(t.effectiveAt(), t.spyMark()).orElse(null);
      }
    }
    return null;
  }

  static BigDecimal netContributionsAt(List<TransferRow> transfers, Instant t) {
    BigDecimal nc = Money.ZERO;
    for (TransferRow tr : transfers) {
      if (tr.contribution() && !tr.effectiveAt().isAfter(t)) {
        nc = nc.add(tr.amount());
      }
    }
    return nc;
  }
}
