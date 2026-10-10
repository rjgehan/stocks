package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.NotificationKind;
import com.stocks.common.events.CorporateActionEffect;
import com.stocks.common.id.PublicId;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.marketdata.CorporateActionSource;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import com.stocks.trading.engine.EngineThread;
import com.stocks.trading.engine.write.DividendWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@code CORPORATE_ACTION_SYNC} end to end with a scripted {@link CorporateActionSource} (§14): the symbols it asks
 * for (held, open-order, watch-listed, the benchmark), the window, the upsert and the immediate application of an
 * overdue action; the job schedules (§25); the store's nullable columns and the {@code daily_mark.dividend_ex} MERGE
 * (matched and not matched) on H2 and, under {@code -Ppg-it}, PostgreSQL; and the player notification.
 */
class SyncJobAndStoreTest extends CorporateActionTestSupport {

  @Autowired PublicId publicIds;
  @Autowired MarketCalendar calendar;
  @Autowired ApplicationEventPublisher events;
  @Autowired ApplicationContext context;
  @Autowired DividendWriter dividends;
  @Autowired CorporateActionApplyJob applyJob;
  @Autowired PayDividendsJob payJob;

  @Test
  void theSyncAsksForTrackedSymbolsAndAppliesWhatIsAlreadyOverdue() {
    Player p = human("1000.00");
    String held = symbol();
    buy(p, held, "2", "10.00");
    AtomicReference<Set<String>> asked = new AtomicReference<>();
    List<LocalDate> window = new ArrayList<>();
    String ref = "sync:" + next();
    CorporateActionSource source = (symbols, from, to) -> {
      asked.set(symbols);
      window.add(from);
      window.add(to);
      return List.of(new CorporateActionData(ref, held.toLowerCase(), CorporateActionType.CASH_DIVIDEND, FRI, FRI,
          WED, null, null, new BigDecimal("0.25"), null));
    };
    CorporateActionSyncJob job = new CorporateActionSyncJob(source, store, publicIds, time, calendar, jdbc, events,
        context.getBeanProvider(CorporateActionApplyJob.class), "SPY");
    time.set(et("2026-10-10T02:30:00"));                                // Saturday's sync: Friday's ex-date passed
    job.run("2026-10-10");
    assertThat(asked.get()).contains(held, "SPY");
    assertThat(window).containsExactly(LocalDate.of(2026, 10, 3), LocalDate.of(2027, 1, 8));
    CorporateActionRow row = store.byProviderRef(ref).orElseThrow();
    assertThat(row.symbol()).isEqualTo(held);
    assertThat(row.origin()).isEqualTo(CorporateActionOrigin.PROVIDER);
    assertThat(row.status()).as("found after its ex-date: applied at once").isEqualTo(CorporateActionStatus.APPLIED);
    // bought Friday 10:00, after Friday's pre-open: not entitled to a Friday ex-date dividend
    assertThat(account(p).dividendsReceivable()).isEqualByComparingTo("0.00");
    job.run("2026-10-10");                                              // a second run changes nothing
    assertThat(store.byProviderRef(ref).orElseThrow().status()).isEqualTo(CorporateActionStatus.APPLIED);
  }

  @Test
  void schedulesFollowTheSpec() {
    // §25: sync 02:30 daily, apply 03:45 and pay 06:00 on trading days
    List<JobOccurrence> syncs = sync.occurrencesBetween(et("2026-10-09T03:00:00"), et("2026-10-12T03:00:00"),
        calendar);
    assertThat(syncs).extracting(JobOccurrence::runKey).containsExactly("2026-10-10", "2026-10-11", "2026-10-12");
    assertThat(syncs.getFirst().at()).isEqualTo(et("2026-10-10T02:30:00"));
    assertThat(applyJob.occurrencesBetween(et("2026-10-09T03:00:00"), et("2026-10-12T04:00:00"), calendar))
        .extracting(JobOccurrence::runKey).containsExactly("2026-10-09", "2026-10-12");
    assertThat(payJob.occurrencesBetween(et("2026-10-09T03:00:00"), et("2026-10-12T06:00:00"), calendar))
        .extracting(JobOccurrence::at).containsExactly(et("2026-10-09T06:00:00"), et("2026-10-12T06:00:00"));
    assertThat(applyJob.catchUp()).isEqualTo(com.stocks.contract.jobs.CatchUpPolicy.ALL_MISSED);
    assertThat(payJob.catchUp()).isEqualTo(com.stocks.contract.jobs.CatchUpPolicy.ALL_MISSED);
    assertThat(sync.catchUp()).isEqualTo(com.stocks.contract.jobs.CatchUpPolicy.LATEST_ONLY);
  }

  @Test
  void nullableColumnsRoundTrip() {
    String id = publicIds.next(com.stocks.common.id.Prefix.CORPORATE_ACTION);
    long rowId = store.insert(new CorporateActionStore.NewAction(id, null, "ZZNULL", CorporateActionType.SPIN_OFF,
        MON, null, null, null, null, null, null, CorporateActionStatus.NEEDS_ADMIN, CorporateActionOrigin.MANUAL, null,
        now()));
    CorporateActionRow r = store.byId(rowId).orElseThrow();
    assertThat(r.providerRef()).isNull();
    assertThat(r.recordDate()).isNull();
    assertThat(r.oldRate()).isNull();
    assertThat(r.detail()).isNull();
    assertThat(r.appliedAt()).isNull();
    assertThat(r.resolvedBy()).isNull();
    assertThat(store.transition(rowId, CorporateActionStatus.PENDING, CorporateActionStatus.APPLIED, "x", now(),
        null)).as("wrong expected status").isFalse();
    assertThat(store.transition(rowId, CorporateActionStatus.NEEDS_ADMIN, CorporateActionStatus.SKIPPED,
        "x".repeat(1200), null, null)).isTrue();
    CorporateActionRow skipped = store.byId(rowId).orElseThrow();
    assertThat(skipped.detail()).hasSize(1000);
    assertThat(skipped.appliedAt()).isNull();
    // rates are normalized for NUMERIC(19,8)
    assertThat(CorporateActionStore.normalizeRate(new BigDecimal("1.123456789"))).isEqualByComparingTo("1.12345679");
    assertThat(CorporateActionStore.normalizeRate(new BigDecimal("2.00000"))).isEqualTo(new BigDecimal("2"));
  }

  @Test
  void dividendExAddsUpWhetherOrNotTheDayHasAMarkRow() {
    String s = symbol();
    EngineThread.runAsEngine(() -> {
      dividends.addDividendEx(s, MON, new BigDecimal("0.25"), now());           // NOT MATCHED: new row
      dividends.addDividendEx(s, MON, new BigDecimal("1.0000001"), now());      // MATCHED: a special dividend adds
      return null;
    });
    var row = jdbc.sql("SELECT dividend_ex, quality, official_close, day_end_mark FROM daily_mark"
        + " WHERE symbol = ? AND trade_date = ?").params(s, MON).query().singleRow();
    assertThat((BigDecimal) row.get("dividend_ex")).isEqualByComparingTo("1.250000");
    assertThat(row.get("quality")).isEqualTo("PROVISIONAL");
    assertThat(row.get("official_close")).isNull();
    assertThat(row.get("day_end_mark")).isNull();
  }

  @Test
  void anEffectBecomesOneCorporateActionNotification() throws InterruptedException {
    Player p = human("500.00");
    events.publishEvent(new CorporateActionEffect(p.accountId(), p.id(), "cac_test" + next(),
        CorporateActionType.FORWARD_SPLIT, "XYZ", "SPLIT", "XYZ 2-for-1 split", "Your 1 shares are now 2 shares.",
        now()));
    long n = 0;
    for (int i = 0; i < 200 && n == 0; i++) {                           // the notifier runs on the eventsExecutor
      n = jdbc.sql("SELECT COUNT(*) FROM notification WHERE player_id = ? AND kind = ?")
          .params(p.id(), NotificationKind.CORPORATE_ACTION.name()).query(Long.class).single();
      if (n == 0) {
        Thread.sleep(25);
      }
    }
    assertThat(n).isEqualTo(1);
    var note = jdbc.sql("SELECT title, body, source_ref FROM notification WHERE player_id = ?").param(p.id())
        .query().singleRow();
    assertThat(note.get("title")).isEqualTo("XYZ 2-for-1 split");
    assertThat((String) note.get("source_ref")).startsWith("CA:cac_test").endsWith(":SPLIT");
  }
}
