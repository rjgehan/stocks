package com.stocks.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.portfolio.dto.BenchmarkCurveDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto.EarningsPointDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto.SymbolPnlDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.PnlCalendarCellDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RacePointDto;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §16.10 P&amp;L analysis on a fresh database (Mon–Fri fallback calendar, 20:00 ET cut-off): the by-symbol split
 * reconciles to the run's P&amp;L (a buy, a partial sell, a dividend accrued and paid, a rename and a P&amp;L
 * correction); the calendar never counts a deposit, chains unit values across days and months, and ends with today's
 * live cell; earnings are returns against SPY and QQQ over the same window.
 */
class PnlAnalysisTest {

  static final Instant MON_10 = Instant.parse("2026-10-05T14:00:00Z");
  static final Instant MON_EOD = Instant.parse("2026-10-06T00:20:00Z");
  static final Instant TUE_10 = Instant.parse("2026-10-06T14:00:00Z");
  static final Instant TUE_11 = Instant.parse("2026-10-06T15:00:00Z");
  static final Instant TUE_EOD = Instant.parse("2026-10-07T00:20:00Z");
  static final Instant WED_10 = Instant.parse("2026-10-07T14:00:00Z");
  static final Instant WED_11 = Instant.parse("2026-10-07T15:00:00Z");
  static final LocalDate FRI_BEFORE = LocalDate.of(2026, 10, 2);
  static final LocalDate MON = LocalDate.of(2026, 10, 5);
  static final LocalDate TUE = LocalDate.of(2026, 10, 6);
  static final LocalDate WED = LocalDate.of(2026, 10, 7);

  PortfolioKit kit;
  PortfolioData data;
  PnlAnalysisService analysis;
  PlayerRef alice;
  AccountRef acc;

  @AfterEach
  void closeDb() {
    kit.close();
  }

  @BeforeEach
  void setUp() {
    kit = new PortfolioKit();
    data = kit.data;
    analysis = new PnlAnalysisService(kit.repository, kit.eodRepository, kit.snapshots, kit.anchors, kit.periods,
        kit.pnlCalendar, kit.valuation, kit.sampler, kit.benchmark, kit.accounts, kit.market, kit.db.jdbc(),
        kit.time);
    kit.at(MON_10);
    alice = data.human("alice");
    acc = data.open(alice, "1000.00");
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  static SymbolPnlDto symbol(PnlAnalysisDto a, String symbol) {
    return a.bySymbol().stream().filter(s -> s.symbol().equals(symbol)).findFirst().orElseThrow();
  }

  static List<BigDecimal> returns(List<RacePointDto> points) {
    return points.stream().map(RacePointDto::ret).toList();
  }

  static void reconciles(PnlAnalysisDto a) {
    BigDecimal sum = a.other();
    for (SymbolPnlDto s : a.bySymbol()) {
      assertThat(s.total()).as(s.symbol()).isEqualByComparingTo(s.realized().add(s.unrealized()).add(s.dividends()));
      sum = sum.add(s.total());
    }
    assertThat(sum).as("Σ total + other = pnl").isEqualByComparingTo(a.pnl());
  }

  // ------------------------------------------------------------------------------------------- by symbol

  @Test
  void pnlBySymbolReconcilesWithASellADividendARenameAndACorrection() {
    data.buy(acc, "AAA", "10", "50.00");
    data.buy(acc, "BBB", "4", "25.00");
    kit.market.dayEndMark("AAA", MON, "52.00").dayEndMark("BBB", MON, "26.00");
    kit.at(MON_EOD);
    kit.eodJob().runFor(MON);

    kit.market.print("AAA", TUE_10.minusSeconds(60), "55.00", 10).print("BBB", TUE_10.minusSeconds(60), "26.50", 10);
    kit.at(TUE_10);
    data.sell(acc, "AAA", "4", "55.00");                         // realized +20.00
    long div = data.accrueDividend(acc, "BBB", "2.00", WED);     // ex-date: a receivable
    kit.at(TUE_11);
    data.rename(acc, "BBB", "CCC");
    data.pnlCorrection(acc, "5.00");
    kit.market.dayEndMark("AAA", TUE, "54.00").dayEndMark("CCC", TUE, "26.50");
    kit.at(TUE_EOD);
    kit.eodJob().runFor(TUE);

    kit.at(WED_10);
    data.payDividend(acc, div);
    kit.market.print("AAA", WED_11.minusSeconds(60), "56.00", 10).print("CCC", WED_11.minusSeconds(60), "27.00", 10);
    kit.at(WED_11);

    PnlAnalysisDto all = analysis.analysis(acc.id(), HistoryRange.ALL);
    assertThat(all.from()).isEqualTo(MON_10);
    assertThat(all.pnl()).isEqualByComparingTo("71.00");           // E 1,071 − NC 1,000
    assertThat(all.bySymbol()).extracting(SymbolPnlDto::symbol).containsExactly("AAA", "CCC");
    SymbolPnlDto aaa = symbol(all, "AAA");
    assertThat(aaa.total()).isEqualByComparingTo("56.00");
    assertThat(aaa.realized()).isEqualByComparingTo("20.00");
    assertThat(aaa.unrealized()).isEqualByComparingTo("36.00");      // 6 × (56 − 50)
    assertThat(aaa.dividends()).isEqualByComparingTo("0.00");
    assertThat(aaa.held()).isTrue();
    SymbolPnlDto ccc = symbol(all, "CCC");                           // BBB's buy and dividend, under its new name
    assertThat(ccc.total()).isEqualByComparingTo("10.00");
    assertThat(ccc.dividends()).isEqualByComparingTo("2.00");
    assertThat(ccc.unrealized()).isEqualByComparingTo("8.00");
    assertThat(ccc.realized()).isEqualByComparingTo("0.00");
    assertThat(all.other()).as("the P&L correction belongs to no symbol").isEqualByComparingTo("5.00");
    reconciles(all);

    // from Tuesday's EOD: the anchor's holdings and receivable; the paid dividend nets to 0 against the receivable
    PnlAnalysisDto day = analysis.analysis(acc.id(), HistoryRange.D1);
    assertThat(day.from()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
    assertThat(day.pnl()).isEqualByComparingTo("14.00");
    assertThat(symbol(day, "AAA").total()).isEqualByComparingTo("12.00");   // 6 × (56 − 54)
    assertThat(symbol(day, "CCC").total()).isEqualByComparingTo("2.00");    // 4 × (27 − 26.50)
    assertThat(symbol(day, "CCC").dividends()).isEqualByComparingTo("0.00");
    assertThat(day.other()).isEqualByComparingTo("0.00");
    reconciles(day);

    // a closed position is reported, not held
    data.sell(acc, "CCC", "4", "27.00");
    PnlAnalysisDto after = analysis.analysis(acc.id(), HistoryRange.ALL);
    assertThat(symbol(after, "CCC").held()).isFalse();
    assertThat(symbol(after, "CCC").realized()).isEqualByComparingTo("8.00");
    reconciles(after);
  }

  // ------------------------------------------------------------------------------------------- earnings

  @Test
  void earningsAreReturnsAgainstSpyAndQqqOverTheSameWindow() {
    data.buy(acc, "AAA", "10", "50.00");
    kit.benchmark.write("SPY", FRI_BEFORE, d("500.0000"), BigDecimal.ZERO);
    kit.benchmark.write("QQQ", FRI_BEFORE, d("400.0000"), BigDecimal.ZERO);
    qqqMinute(MON_10.minusSeconds(60), "400.00");                       // QQQ at the opening: unchanged
    kit.market.dayEndMark("AAA", MON, "52.00").dayEndMark("SPY", MON, "505.00").dayEndMark("QQQ", MON, "404.00");
    kit.at(MON_EOD);
    kit.eodJob().runFor(MON);
    kit.market.dayEndMark("AAA", TUE, "54.00").dayEndMark("SPY", TUE, "510.00").dayEndMark("QQQ", TUE, "412.00");
    kit.at(TUE_EOD);
    kit.eodJob().runFor(TUE);
    kit.market.print("AAA", WED_11.minusSeconds(60), "55.00", 10).print("SPY", WED_11.minusSeconds(60), "515.00", 10)
        .print("QQQ", WED_11.minusSeconds(60), "416.00", 10);
    kit.at(WED_11);

    PnlAnalysisDto w = analysis.analysis(acc.id(), HistoryRange.W1);
    assertThat(w.range()).isEqualTo(HistoryRange.W1);
    assertThat(w.from()).as("the range starts before the opening: at the opening").isEqualTo(MON_10);
    assertThat(w.points()).extracting(EarningsPointDto::ret).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.02"), d("0.04"), d("0.05"));
    assertThat(w.points()).extracting(EarningsPointDto::pnl).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("20"), d("40"), d("50"));
    assertThat(w.ret()).isEqualByComparingTo("0.05");
    assertThat(w.benchmarks()).extracting(BenchmarkCurveDto::symbol).containsExactly("SPY", "QQQ");
    BenchmarkCurveDto spy = w.benchmarks().get(0);
    assertThat(returns(spy.points())).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.01"), d("0.02"), d("0.03"));
    assertThat(spy.ret()).isEqualByComparingTo("0.03");
    BenchmarkCurveDto qqq = w.benchmarks().get(1);
    assertThat(returns(qqq.points())).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(d("0"), d("0.01"), d("0.03"), d("0.04"));
    assertThat(qqq.ret()).isEqualByComparingTo("0.04");

    // 1D: Tuesday's EOD then the samples and live; the benchmarks have no intraday rows
    kit.sampler.sample(WED_11.minusSeconds(30));
    PnlAnalysisDto d1 = analysis.analysis(acc.id(), HistoryRange.D1);
    assertThat(d1.points()).hasSize(3);
    assertThat(d1.points().get(2).ret()).isEqualByComparingTo(
        d("1050").divide(d("1040"), 10, RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE));
    assertThat(returns(d1.benchmarks().get(0).points())).hasSize(2);
    assertThat(analysis.analysis(acc.id(), null).range()).isEqualTo(HistoryRange.M1);

    // a benchmark without rows has no curve
    kit.db.jdbc().sql("DELETE FROM benchmark_daily WHERE symbol = 'QQQ'").update();
    assertThat(analysis.analysis(acc.id(), HistoryRange.W1).benchmarks().get(1).ret()).isNull();
    assertThatThrownBy(() -> analysis.analysis(999_999L, HistoryRange.W1))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
  }

  void qqqMinute(Instant start, String close) {
    BigDecimal c = d(close);
    kit.market.scriptBar(new Bar("QQQ", Timeframe.MIN1, start, c, c, c, c, 1_000, null, 1, false));
  }

  /**
   * Review finding: a run opened mid-session started QQQ at the previous EOD (so its line counted the move before the
   * opening) while SPY started at the opening's mark. QQQ now starts at its price at the opening (its 1-minute bars),
   * and has no curve while that price is unknown.
   */
  @Test
  void anIntradayOpeningStartsQqqAtItsPriceThenNotAtThePreviousClose() {
    kit.benchmark.write("SPY", FRI_BEFORE, d("500.0000"), BigDecimal.ZERO);
    kit.benchmark.write("QQQ", FRI_BEFORE, d("400.0000"), BigDecimal.ZERO);
    kit.market.print("SPY", MON_10.plusSeconds(60), "505.00", 10).print("QQQ", MON_10.plusSeconds(60), "404.00", 10);
    kit.at(MON_10.plusSeconds(120));

    // no bar yet (the provider's bars lag): no QQQ curve, never one from Friday's close
    PnlAnalysisDto early = analysis.analysis(acc.id(), HistoryRange.W1);
    assertThat(early.benchmarks().get(1).symbol()).isEqualTo("QQQ");
    assertThat(early.benchmarks().get(1).ret()).isNull();
    assertThat(early.benchmarks().get(1).points()).isEmpty();

    // QQQ was already up 1% when the run opened: its line starts there (404 / 404 − 1 = 0), like SPY's
    qqqMinute(MON_10.minusSeconds(60), "404.00");
    kit.at(MON_10.plusSeconds(120).plus(PnlAnalysisService.OPENING_MISS_RETRY));
    PnlAnalysisDto w = analysis.analysis(acc.id(), HistoryRange.W1);
    BenchmarkCurveDto qqq = w.benchmarks().get(1);
    assertThat(qqq.points().getFirst().t()).isEqualTo(MON_10);
    assertThat(qqq.ret()).isEqualByComparingTo("0");

    // an opening before the regular open starts at the last EOD: nothing has moved since
    assertThat(analysis.extraIndexAtOpening("QQQ", Instant.parse("2026-10-05T12:00:00Z")))
        .isEqualByComparingTo(kit.benchmark.indexAtEod("QQQ", FRI_BEFORE).orElseThrow());
    // ... unless QQQ already traded pre-market: then from that trade, like SPY's mark at the opening
    qqqMinute(Instant.parse("2026-10-05T11:30:00Z"), "402.00");
    Instant preMarket = Instant.parse("2026-10-05T11:45:00Z");
    assertThat(analysis.extraIndexAtOpening("QQQ", preMarket))
        .isEqualByComparingTo(kit.benchmark.indexAt("QQQ", preMarket, d("402.00")).orElseThrow());
  }

  // ------------------------------------------------------------------------------------------- calendar

  @Test
  void theCalendarNeverCountsADepositAndEndsWithTodaysLiveCell() {
    data.buy(acc, "AAA", "10", "50.00");
    kit.market.dayEndMark("AAA", MON, "52.00");
    kit.at(MON_EOD);
    kit.eodJob().runFor(MON);
    kit.market.print("AAA", TUE_11.minusSeconds(60), "53.00", 10);
    kit.at(TUE_11);
    data.deposit(acc, "500.00");                                 // at U = 1.03
    kit.market.dayEndMark("AAA", TUE, "54.00");
    kit.at(TUE_EOD);
    kit.eodJob().runFor(TUE);
    kit.market.print("AAA", WED_11.minusSeconds(60), "55.00", 10);
    kit.at(WED_11);

    PnlCalendarDto oct = analysis.calendar(acc.id(), CalendarView.DAY, YearMonth.of(2026, 10), null);
    assertThat(oct.view()).isEqualTo(CalendarView.DAY);
    assertThat(oct.from()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(oct.to()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(oct.cells()).extracting(PnlCalendarCellDto::date).containsExactly(MON, TUE, WED);
    PnlCalendarCellDto mon = oct.cells().get(0);
    assertThat(mon.pnl()).isEqualByComparingTo("20.00");
    assertThat(mon.ret()).isEqualByComparingTo("0.02");
    assertThat(mon.flows()).as("the run's own funding is not a deposit").isEqualByComparingTo("0.00");
    PnlCalendarCellDto tue = oct.cells().get(1);
    assertThat(tue.pnl()).as("1,540 − 1,020 − 500: only the price move").isEqualByComparingTo("20.00");
    assertThat(tue.flows()).isEqualByComparingTo("500.00");
    BigDecimal uTue = kit.eodRepository.find(acc.id(), TUE).orElseThrow().unitValue();
    assertThat(tue.ret()).isEqualByComparingTo(MetricsCalculator.ratioMinusOne(uTue, d("1.02")));
    PnlCalendarCellDto wed = oct.cells().get(2);
    assertThat(wed.live()).isTrue();
    assertThat(wed.pnl()).isEqualByComparingTo("10.00");
    assertThat(wed.flows()).isEqualByComparingTo("0.00");
    assertThat(mon.live()).isFalse();
    assertThat(oct.total().pnl()).isEqualByComparingTo("50.00");
    BigDecimal uNow = kit.valuation.valueNow(acc.id()).unitValue();
    assertThat(oct.total().ret()).isEqualByComparingTo(MetricsCalculator.ratioMinusOne(uNow, BigDecimal.ONE));
    assertThat(analysis.calendar(acc.id(), null, null, null).cells()).as("default: DAY, this ET month").hasSize(3);

    PnlCalendarDto year = analysis.calendar(acc.id(), CalendarView.MONTH, null, 2026);
    assertThat(year.from()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(year.cells()).singleElement().satisfies(c -> {
      assertThat(c.date()).isEqualTo(LocalDate.of(2026, 10, 1));
      assertThat(c.pnl()).isEqualByComparingTo("50.00");
      assertThat(c.flows()).isEqualByComparingTo("500.00");
      assertThat(c.live()).isTrue();
      assertThat(c.ret()).isEqualByComparingTo(oct.total().ret());
    });
    PnlCalendarDto sep = analysis.calendar(acc.id(), CalendarView.DAY, YearMonth.of(2026, 9), null);
    assertThat(sep.cells()).isEmpty();
    assertThat(sep.total().pnl()).isEqualByComparingTo("0.00");
    assertThat(sep.total().ret()).isNull();

    // after Wednesday's EOD the live cell is the snapshot
    kit.market.dayEndMark("AAA", WED, "55.00");
    kit.at(Instant.parse("2026-10-08T00:20:00Z"));
    kit.eodJob().runFor(WED);
    PnlCalendarCellDto wedEod = analysis.calendar(acc.id(), CalendarView.DAY, YearMonth.of(2026, 10), null).cells()
        .get(2);
    assertThat(wedEod.live()).isFalse();
    assertThat(wedEod.pnl()).isEqualByComparingTo("10.00");
  }

  @Test
  void aRunOpenedOnTheWeekendHasNoCellUntilItsFirstTradingDay() {
    kit.at(Instant.parse("2026-10-10T15:00:00Z"));               // Saturday
    PlayerRef bob = data.human("bob");
    AccountRef b = data.open(bob, "500.00");
    assertThat(analysis.calendar(b.id(), CalendarView.DAY, YearMonth.of(2026, 10), null).cells()).isEmpty();
    kit.at(Instant.parse("2026-10-12T15:00:00Z"));               // Monday
    assertThat(analysis.calendar(b.id(), CalendarView.DAY, YearMonth.of(2026, 10), null).cells()).singleElement()
        .satisfies(c -> {
          assertThat(c.date()).isEqualTo(LocalDate.of(2026, 10, 12));
          assertThat(c.live()).isTrue();
          assertThat(c.pnl()).isEqualByComparingTo("0.00");
          assertThat(c.flows()).isEqualByComparingTo("0.00");
        });
  }
}
