package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.HoldingsReconstructor.Holding;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Valuation (§16.1), holdings replay and EOD snapshots (§16.4), intraday samples and the benchmark (§16.6) on a fresh
 * database (H2, or embedded PostgreSQL under {@code -Ppg-it}: every MERGE here runs on both).
 */
class ValuationAndSnapshotsTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");      // Mon 10:00 ET
  static final LocalDate MON = LocalDate.of(2026, 10, 5);
  static final LocalDate TUE = LocalDate.of(2026, 10, 6);
  static final Instant MON_CUTOFF = Instant.parse("2026-10-06T00:00:00Z");  // Mon 20:00 ET
  static final Instant TUE_CUTOFF = Instant.parse("2026-10-07T00:00:00Z");

  PortfolioKit kit;
  PlayerRef alice;
  AccountRef acc;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    kit = new PortfolioKit();
    kit.at(MON_10);
    alice = kit.data.human("alice");
    acc = kit.data.open(alice, "500.00");
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  // ------------------------------------------------------------------------------------------- valuation

  @Test
  void equityIsCashPlusMarkedPositionsAndUnitValueFollows() {
    kit.data.buy(acc, "AAPL", "2", "100.00");
    kit.market.print("AAPL", MON_10.plusSeconds(60), "110.00", 100);
    kit.at(MON_10.plusSeconds(120));
    Valuation v = kit.valuation.valueNow(acc.id());
    assertThat(v.cash()).isEqualByComparingTo("300.00");
    assertThat(v.longMarketValue()).isEqualByComparingTo("220.00");
    assertThat(v.dividendsReceivable()).isEqualByComparingTo("0.00");
    assertThat(v.equity()).isEqualByComparingTo("520.00").satisfies(e -> assertThat(e.scale()).isEqualTo(2));
    assertThat(v.netContributions()).isEqualByComparingTo("500.00");
    assertThat(v.units()).isEqualByComparingTo("500");
    assertThat(v.unitValue()).isEqualByComparingTo("1.04").satisfies(u -> assertThat(u.scale()).isEqualTo(18));
    assertThat(v.unitSeries()).isEqualTo(1);
    assertThat(v.marksQuality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(v.marks()).containsOnlyKeys("AAPL");
    assertThat(v.asOf()).isEqualTo(MON_10.plusSeconds(120));
    assertThat(kit.valuation.valueAllActive()).containsOnlyKeys(acc.id())
        .hasEntrySatisfying(acc.id(), all -> assertThat(all.equity()).isEqualByComparingTo("520.00"));
  }

  @Test
  void withoutAnyMarketPriceTheLastFillThenTheCostIsUsedAndTheQualitySaysSo() {
    kit.data.buy(acc, "NOMK", "3", "10.00");                     // NOMK has no quote at all: last fill (STALE)
    Valuation v = kit.valuation.valueNow(acc.id());
    assertThat(v.marks().get("NOMK").price()).isEqualByComparingTo("10.00");
    assertThat(v.marks().get("NOMK").quality()).isEqualTo(DataQuality.STALE);
    assertThat(v.marksQuality()).isEqualTo(DataQuality.STALE);
    assertThat(v.equity()).isEqualByComparingTo("500.00");
    // a position without any fill in the league (e.g. moved by a corporate action): its average cost, quality NONE
    Mark cost = kit.marks.live("GHOST", d("4"), d("20.00"));
    assertThat(cost.price()).isEqualByComparingTo("5.0000");
    assertThat(cost.quality()).isEqualTo(DataQuality.NONE);
    assertThat(kit.marks.live("GHOST", null, null).price()).isEqualByComparingTo("0");
  }

  @Test
  void anAccountWithoutUnitsKeepsItsLastUnitValue() {
    kit.data.withdraw(acc, "500.00");
    Valuation v = kit.valuation.valueNow(acc.id());
    assertThat(v.units()).isEqualByComparingTo("0");
    assertThat(v.equity()).isEqualByComparingTo("0.00");
    assertThat(v.unitValue()).isEqualByComparingTo("1");
  }

  @Test
  void anUnknownAccountIsNotFound() {
    assertThatThrownBy(() -> kit.valuation.valueNow(987654L))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
  }

  @Test
  void dividendContinuity() {                                     // golden/performance/dividend_continuity.json
    kit.data.buy(acc, "DIVY", "10", "50.00");
    kit.market.print("DIVY", Instant.parse("2026-10-07T18:00:00Z"), "50.00", 100);
    kit.at(Instant.parse("2026-10-07T19:00:00Z"));
    assertValue("0.00", "500.00", "0.00", "500.00");
    kit.market.print("DIVY", Instant.parse("2026-10-08T07:59:00Z"), "49.50", 100);   // ex-date: the price drops
    kit.at(Instant.parse("2026-10-08T08:00:00Z"));
    long ent = kit.data.accrueDividend(acc, "DIVY", "5.00", LocalDate.of(2026, 10, 9));
    assertValue("0.00", "495.00", "5.00", "500.00");
    kit.at(Instant.parse("2026-10-09T09:59:59Z"));
    assertValue("0.00", "495.00", "5.00", "500.00");
    kit.at(Instant.parse("2026-10-09T10:00:00Z"));
    kit.data.payDividend(acc, ent);
    assertValue("5.00", "495.00", "0.00", "500.00");               // the $5.00 counts once
    // point in time: between accrual and payment, the receivable is visible as of then, not now
    assertThat(kit.repository.receivableAt(acc.id(), Instant.parse("2026-10-08T12:00:00Z")))
        .isEqualByComparingTo("5.00");
    assertThat(kit.repository.cashAt(acc.id(), Instant.parse("2026-10-08T12:00:00Z")))
        .isEqualByComparingTo("0.00");
  }

  private void assertValue(String cash, String lmv, String receivable, String equity) {
    Valuation v = kit.valuation.valueNow(acc.id());
    assertThat(v.cash()).as("cash").isEqualByComparingTo(cash);
    assertThat(v.longMarketValue()).as("lmv").isEqualByComparingTo(lmv);
    assertThat(v.dividendsReceivable()).as("receivable").isEqualByComparingTo(receivable);
    assertThat(v.equity()).as("equity").isEqualByComparingTo(equity);
    assertThat(kit.valuation.valueAllActive().get(acc.id()).equity()).isEqualByComparingTo(equity);
  }

  // ------------------------------------------------------------------------------------------- holdings replay

  @Test
  void holdingsAreReplayedForwardThroughFillsSplitsAndRenames() {
    kit.data.buy(acc, "OLD", "4", "25.00");
    kit.at(MON_10.plusSeconds(600));
    kit.data.sell(acc, "OLD", "1", "30.00");
    kit.at(MON_10.plusSeconds(1200));
    kit.data.rename(acc, "OLD", "NEW");
    Map<String, Holding> before = kit.holdings.holdingsAt(acc.id(), MON_10.plusSeconds(1));
    assertThat(before).containsOnlyKeys("OLD");
    assertThat(before.get("OLD").qty()).isEqualByComparingTo("4");
    assertThat(before.get("OLD").costBasis()).isEqualByComparingTo("100.00");
    Map<String, Holding> mid = kit.holdings.holdingsAt(acc.id(), MON_10.plusSeconds(601));
    assertThat(mid.get("OLD").qty()).isEqualByComparingTo("3");
    Map<String, Holding> after = kit.holdings.holdingsAt(acc.id(), MON_10.plusSeconds(1201));
    assertThat(after).containsOnlyKeys("NEW");
    assertThat(after.get("NEW").qty()).isEqualByComparingTo("3");
    assertThat(after.get("NEW").costBasis()).isEqualByComparingTo("75.00");
    assertThat(kit.holdings.holdingsAt(acc.id(), MON_10.minusSeconds(1))).isEmpty();
  }

  // ------------------------------------------------------------------------------------------- EOD

  @Test
  void theEodJobSnapshotsEveryOpenRunIdempotently() {
    kit.data.buy(acc, "AAPL", "2", "100.00");
    kit.market.dayEndMark("AAPL", MON, "110.00").dayEndMark("SPY", MON, "500.00");
    EodSnapshotJob job = kit.eodJob();
    kit.at(MON_CUTOFF.minusSeconds(1));
    assertThatThrownBy(() -> job.runFor(MON)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> job.runFor(LocalDate.of(2026, 10, 10))).isInstanceOf(IllegalArgumentException.class);
    kit.at(MON_CUTOFF.plusSeconds(20 * 60));
    assertThat(job.runFor(MON)).isEqualTo(1);
    job.run(MON.toString());                                       // the scheduler's entry point: same rows again
    EodSnapshot s = kit.eodRepository.find(acc.id(), MON).orElseThrow();
    assertThat(s.persisted()).isTrue();
    assertThat(s.asOf()).isEqualTo(MON_CUTOFF);
    assertThat(s.cash()).isEqualByComparingTo("300.00");
    assertThat(s.longMarketValue()).isEqualByComparingTo("220.00");
    assertThat(s.equity()).isEqualByComparingTo("520.00");
    assertThat(s.netContributions()).isEqualByComparingTo("500.00");
    assertThat(s.unitValue()).isEqualByComparingTo("1.04");
    assertThat(s.flowsInDay()).isEqualByComparingTo("500.00");     // opened during Monday: the INITIAL is a flow
    assertThat(s.pnlDay()).isEqualByComparingTo("20.00");
    assertThat(s.fillsDay()).isEqualTo(1);
    assertThat(s.marksQuality()).isEqualTo(DataQuality.REALTIME_CONSOLIDATED);
    assertThat(s.pnlSinceOpen()).isEqualByComparingTo("20.00");
    assertThat(kit.eodRepository.holdings(acc.id(), MON))
        .containsExactly(new HoldingRow("AAPL", d("2.00000"), d("110.0000"), d("220.00")));
    assertThat(kit.db.jdbc().sql("SELECT COUNT(*) FROM equity_snapshot").query(Long.class).single()).isEqualTo(1);
    assertThat(kit.events).hasSize(2).allSatisfy(e -> assertThat(e).isEqualTo(new EodSnapshotsWritten(MON,
        MON_CUTOFF)));
    assertThat(kit.benchmark.day(MON).orElseThrow().indexValue()).isEqualByComparingTo("1");

    // Tuesday: a deposit is a flow, not P&L; the benchmark chains
    kit.at(Instant.parse("2026-10-06T15:00:00Z"));
    kit.market.print("AAPL", Instant.parse("2026-10-06T14:59:00Z"), "120.00", 10);
    kit.data.deposit(acc, "100.00");
    kit.market.dayEndMark("AAPL", TUE, "115.00").dayEndMark("SPY", TUE, "510.00");
    kit.at(TUE_CUTOFF.plusSeconds(20 * 60));
    job.runFor(TUE);
    EodSnapshot t = kit.eodRepository.find(acc.id(), TUE).orElseThrow();
    assertThat(t.equity()).isEqualByComparingTo("630.00");         // 400 cash + 2 × 115
    assertThat(t.flowsInDay()).isEqualByComparingTo("100.00");
    assertThat(t.pnlDay()).isEqualByComparingTo("10.00");          // 630 − 520 − 100
    assertThat(t.fillsDay()).isZero();
    assertThat(kit.benchmark.day(TUE).orElseThrow().indexValue()).isEqualByComparingTo("1.02");
    assertThat(kit.eodRepository.series(List.of(acc.id()), MON.minusDays(1), TUE)).hasSize(2);
    assertThat(kit.eodRepository.byDate(TUE)).containsOnlyKeys(acc.id());
    assertThat(kit.eodRepository.latestDate(acc.id())).contains(TUE);
    assertThat(kit.eodRepository.allAfter(MON)).hasSize(1);
  }

  @Test
  void theEodJobFallsBackToTheLiveMarkJustAfterTheCutoffAndThenToTheLastFill() {
    kit.data.buy(acc, "LIVE", "1", "100.00");
    kit.market.print("LIVE", Instant.parse("2026-10-05T19:59:00Z"), "105.00", 10);
    kit.at(MON_CUTOFF.plusSeconds(20 * 60));
    kit.eodJob().runFor(MON);
    assertThat(kit.eodRepository.find(acc.id(), MON).orElseThrow().equity()).isEqualByComparingTo("505.00");
    // days later (a catch-up), the live mark no longer describes Monday: the last fill at the cut-off is used
    kit.at(Instant.parse("2026-10-09T15:00:00Z"));
    EodSnapshot computed = kit.valuator.compute(kit.repository.account(acc.id()).orElseThrow(), MON).orElseThrow();
    assertThat(computed.persisted()).isFalse();
    assertThat(computed.equity()).isEqualByComparingTo("500.00");
    assertThat(computed.marksQuality()).isEqualTo(DataQuality.STALE);
    assertThat(kit.benchmark.day(MON)).isEmpty();                  // SPY had no usable mark
  }

  @Test
  void eodOccurrencesAreTwentyMinutesAfterEachCutoff() {
    EodSnapshotJob job = kit.eodJob();
    assertThat(job.name()).isEqualTo("EOD_SNAPSHOT");
    assertThat(job.catchUp()).isEqualTo(CatchUpPolicy.ALL_MISSED);
    List<JobOccurrence> occ = job.occurrencesBetween(Instant.parse("2026-10-05T00:00:00Z"),
        Instant.parse("2026-10-10T12:00:00Z"), kit.calendar);
    assertThat(occ).extracting(JobOccurrence::runKey)
        .containsExactly("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09");
    assertThat(occ.get(0).at()).isEqualTo(Instant.parse("2026-10-06T00:20:00Z"));
    assertThat(job.occurrencesBetween(Instant.parse("2026-10-06T00:20:00Z"), Instant.parse("2026-10-06T12:00:00Z"),
        kit.calendar)).isEmpty();
  }

  @Test
  void computedAnchorsAreUsedUntilTheJobWritesAndNeverForTheFuture() {
    kit.data.buy(acc, "AAPL", "1", "100.00");
    kit.market.dayEndMark("AAPL", MON, "90.00");
    var run = kit.repository.account(acc.id()).orElseThrow();
    kit.at(MON_CUTOFF.minusSeconds(60));
    assertThat(kit.snapshots.get(run, MON)).isEmpty();             // the cut-off has not passed
    kit.at(MON_CUTOFF.plusSeconds(60));
    EodSnapshot computed = kit.snapshots.get(run, MON).orElseThrow();
    assertThat(computed.persisted()).isFalse();
    assertThat(computed.equity()).isEqualByComparingTo("490.00");
    assertThat(kit.snapshots.get(run, MON)).containsSame(computed);   // memoized
    assertThat(kit.snapshots.withHoldings(run, MON).orElseThrow().holdings()).hasSize(1);
    assertThat(kit.snapshots.get(run, LocalDate.of(2026, 10, 2))).isEmpty();   // the run did not exist yet
    kit.eodJob().runFor(MON);
    assertThat(kit.snapshots.get(run, MON).orElseThrow().persisted()).isTrue();
    assertThat(kit.snapshots.withHoldings(run, MON).orElseThrow().holdings()).hasSize(1);
  }

  // ------------------------------------------------------------------------------------------- intraday

  @Test
  void intradaySamplesSitOnTheFiveMinuteGridDuringTheTradingDay() {
    IntradaySampler sampler = kit.sampler;
    assertThat(sampler.name()).isEqualTo("INTRADAY_SAMPLER");
    assertThat(sampler.catchUp()).isEqualTo(CatchUpPolicy.NONE);
    List<JobOccurrence> occ = sampler.occurrencesBetween(Instant.parse("2026-10-05T13:58:00Z"),
        Instant.parse("2026-10-05T14:11:00Z"), kit.calendar);
    assertThat(occ).extracting(JobOccurrence::at).containsExactly(Instant.parse("2026-10-05T14:00:00Z"),
        Instant.parse("2026-10-05T14:05:00Z"), Instant.parse("2026-10-05T14:10:00Z"));
    assertThat(occ.get(0).runKey()).isEqualTo("2026-10-05T14:00:00Z");
    // nothing on a Saturday, nothing after the 20:00 ET cut-off, the first sample at the 04:00 pre-open
    assertThat(sampler.occurrencesBetween(Instant.parse("2026-10-10T00:00:00Z"),
        Instant.parse("2026-10-12T07:59:00Z"), kit.calendar)).isEmpty();
    assertThat(sampler.occurrencesBetween(Instant.parse("2026-10-12T07:59:00Z"),
        Instant.parse("2026-10-12T08:01:00Z"), kit.calendar)).extracting(JobOccurrence::at)
        .containsExactly(Instant.parse("2026-10-12T08:00:00Z"));

    kit.at(Instant.parse("2026-10-05T14:05:00Z"));
    sampler.run("2026-10-05T14:05:00Z");
    sampler.run("2026-10-05T14:05:00Z");                            // MERGE: still one row
    sampler.run("2026-10-05T13:00:00Z");                            // too late: skipped
    List<IntradaySampler.Sample> rows = sampler.samples(acc.id(), MON_10, Instant.parse("2026-10-05T15:00:00Z"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).sampledAt()).isEqualTo(Instant.parse("2026-10-05T14:05:00Z"));
    assertThat(rows.get(0).equity()).isEqualByComparingTo("500.00");
    assertThat(rows.get(0).unitValue()).isEqualByComparingTo("1");
    assertThat(rows.get(0).unitSeries()).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------- benchmark

  @Test
  void theBenchmarkChainsTotalReturnAndAnchorsIntraday() {
    BenchmarkService b = kit.benchmark;
    assertThat(b.symbol()).isEqualTo("SPY");
    b.write(MON, d("500.0000"), BigDecimal.ZERO);
    b.write(TUE, d("495.0000"), d("5.000000"));                     // ex-dividend: total return is flat
    assertThat(b.indexAtEod(TUE)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1"));
    assertThat(b.latestOnOrBefore(LocalDate.of(2026, 10, 7)).orElseThrow().tradeDate()).isEqualTo(TUE);
    assertThat(b.latestBefore(TUE).orElseThrow().tradeDate()).isEqualTo(MON);
    assertThat(b.after(MON)).hasSize(1);
    // Wed 11:00 ET at a SPY mark of 504.90: B = 1 × 504.90 / 495
    assertThat(b.indexAt(Instant.parse("2026-10-07T15:00:00Z"), d("504.90")))
        .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.02"));
    // Tue 15:00 ET is before Tuesday's cut-off: Monday's EOD is the base
    assertThat(b.indexAt(Instant.parse("2026-10-06T19:00:00Z"), d("510.00")))
        .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.02"));
    assertThat(b.indexAt(Instant.parse("2026-10-06T19:00:00Z"), null)).isEmpty();
    kit.at(Instant.parse("2026-10-07T15:00:00Z"));
    assertThat(b.liveIndex()).isEmpty();                            // SPY has no live mark
    kit.market.print("SPY", Instant.parse("2026-10-07T14:59:00Z"), "504.90", 100);
    assertThat(b.liveIndex()).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.02"));
    assertThat(b.dividendEx(MON)).isEqualByComparingTo("0");
  }

  @Test
  void theBackfillJobRunsOnceAndFailsWithoutBars() {
    BenchmarkBackfillJob job = new BenchmarkBackfillJob(kit.benchmark, kit.market, kit.pnlCalendar, kit.time);
    assertThat(job.name()).isEqualTo("BENCHMARK_BACKFILL");
    assertThat(job.catchUp()).isEqualTo(CatchUpPolicy.ALL_MISSED);
    Instant from = Instant.parse("2026-10-02T00:00:00Z");
    List<JobOccurrence> occ = job.occurrencesBetween(from, Instant.parse("2026-10-09T00:00:00Z"), kit.calendar);
    assertThat(occ).singleElement().satisfies(o -> {
      assertThat(o.runKey()).isEqualTo("2025-12-31");
      assertThat(o.at()).isEqualTo(from.plusMillis(1));
    });
    assertThat(job.occurrencesBetween(from, from, kit.calendar)).isEmpty();
    assertThatThrownBy(() -> job.run("2025-12-31")).isInstanceOf(IllegalStateException.class);
    assertThat(job.occurrencesBetween(from, Instant.parse("2026-10-09T00:00:00Z"), kit.calendar)).isEmpty();
  }

  @Test
  void aLeagueThatHasSpyBackfillsTheExtraBenchmarkUnderItsOwnRunKey() {
    // production backfilled SPY before Phase 2: job_run(BENCHMARK_BACKFILL, 2025-12-31) is DONE and never runs again,
    // so the QQQ backfill needs a key of its own (§16.6 extra benchmarks)
    kit.benchmark.write(BenchmarkBackfillJob.ANCHOR, d("600.0000"), BigDecimal.ZERO);
    BenchmarkBackfillJob job = new BenchmarkBackfillJob(kit.benchmark, kit.market, kit.pnlCalendar, kit.time);
    Instant from = Instant.parse("2026-10-02T00:00:00Z");
    assertThat(job.missing()).containsExactly("QQQ");
    assertThat(job.occurrencesBetween(from, Instant.parse("2026-10-09T00:00:00Z"), kit.calendar)).singleElement()
        .satisfies(o -> assertThat(o.runKey()).isEqualTo("2025-12-31:QQQ")
            .matches(com.stocks.jobs.JobScheduler.RUN_KEY));
    assertThat(job.runKey(List.of("SPY", "QQQ"))).isEqualTo("2025-12-31");
    assertThat(job.runKey(List.of("QQQ", "DIA"))).isEqualTo("2025-12-31:QQQ+DIA");
    kit.benchmark.write("QQQ", BenchmarkBackfillJob.ANCHOR, d("520.0000"), BigDecimal.ZERO);
    assertThat(job.occurrencesBetween(from, Instant.parse("2026-10-09T00:00:00Z"), kit.calendar)).isEmpty();
  }

  @Test
  void theBackfillJobWritesTheChainFromDailyBars() {
    com.stocks.testkit.ScriptedMarketData bars = new com.stocks.testkit.ScriptedMarketData(kit.time) {
      @Override
      public com.stocks.contract.marketdata.BarSeries bars(com.stocks.contract.marketdata.BarRequest r) {
        assertThat(r.adjustment()).isEqualTo(com.stocks.contract.marketdata.model.AdjustmentMode.RAW);
        if (!r.symbol().equals("SPY")) {                            // the extra benchmark (QQQ) has no bars here
          return new com.stocks.contract.marketdata.BarSeries(r.symbol(), com.stocks.common.domain.Timeframe.DAY1,
              r.adjustment(), List.of(), null, "test");
        }
        return new com.stocks.contract.marketdata.BarSeries("SPY", com.stocks.common.domain.Timeframe.DAY1,
            r.adjustment(), List.of(bar("2025-12-31", "600"), bar("2026-01-02", "606"), bar("2026-10-08", "660")),
            null, "test");
      }
    };
    kit.at(Instant.parse("2026-10-09T15:00:00Z"));
    kit.benchmark.write(LocalDate.of(2026, 10, 9), d("666.0000"), BigDecimal.ZERO);   // written by an EOD before
    BenchmarkBackfillJob job = new BenchmarkBackfillJob(kit.benchmark, bars, kit.pnlCalendar, kit.time);
    assertThat(job.backfill()).isEqualTo(3);
    assertThat(kit.benchmark.indexAtEod(LocalDate.of(2025, 12, 31))).hasValueSatisfying(v ->
        assertThat(v).isEqualByComparingTo("1"));
    assertThat(kit.benchmark.indexAtEod(LocalDate.of(2026, 1, 2))).hasValueSatisfying(v ->
        assertThat(v).isEqualByComparingTo("1.01"));
    assertThat(kit.benchmark.indexAtEod(LocalDate.of(2026, 10, 9))).hasValueSatisfying(v ->
        assertThat(v).isEqualByComparingTo("1.11"));                 // re-chained onto the backfill
  }

  static com.stocks.contract.marketdata.model.Bar bar(String date, String close) {
    Instant start = com.stocks.common.time.Et.startOfDay(LocalDate.parse(date));
    BigDecimal c = new BigDecimal(close);
    return new com.stocks.contract.marketdata.model.Bar("SPY", com.stocks.common.domain.Timeframe.DAY1, start, c, c, c,
        c, 1000, c, 10, false);
  }
}
