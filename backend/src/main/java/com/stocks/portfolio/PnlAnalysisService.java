package com.stocks.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.marketdata.BarRequest;
import com.stocks.contract.marketdata.BarSeries;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.portfolio.dto.BenchmarkCurveDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto.EarningsPointDto;
import com.stocks.contract.portfolio.dto.PnlAnalysisDto.SymbolPnlDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.CalendarView;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.PnlCalendarCellDto;
import com.stocks.contract.portfolio.dto.PnlCalendarDto.PnlCalendarTotalDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RacePointDto;
import com.stocks.portfolio.BenchmarkService.BenchmarkDay;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.IntradaySampler.Sample;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.PeriodResolver.Window;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.PositionRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import com.stocks.portfolio.RunAnchors.Anchor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link PnlAnalysisQueries} (§16.10, Phase 2; Webull "P&amp;L Analysis"), run-relative like the account page (§16.3):
 * <ul>
 *   <li><b>My Earnings</b>: the run's curve over a history range as returns {@code U(t) / U(a) − 1} with the P&amp;L
 *       since the anchor (the same anchor, EOD snapshots / 5-minute samples and live end as
 *       {@code GET /account/history}), and SPY plus the extra benchmarks (QQQ) as {@code B(t) / B(a) − 1}.</li>
 *   <li><b>P&amp;L by symbol</b>: {@code total(s) = MV_b − MV_a + Σ net_amount + dividends + cash in lieu}, split into
 *       realized (sells and cash in lieu), dividends (paid plus the change of the receivable) and the change of
 *       unrealized P&amp;L; {@code Σ total + other = pnl}, {@code other} being P&amp;L corrections.</li>
 *   <li><b>P&amp;L calendar</b>: one cell per EOD snapshot of the run ({@code pnl_day}: flows never count, corporate
 *       actions leave equity unchanged), plus today's live cell until the EOD job writes it; months sum their days
 *       and chain their unit values.</li>
 * </ul>
 */
@Service
public class PnlAnalysisService implements PnlAnalysisQueries {

  /** Rename chains are followed at most this far (§16.4). */
  private static final int MAX_RENAME_HOPS = 16;

  private final PortfolioRepository repository;
  private final EodSnapshotRepository snapshots;
  private final EodSnapshots eod;
  private final RunAnchors anchors;
  private final PeriodResolver periods;
  private final PnlCalendar calendar;
  private final ValuationService valuation;
  private final IntradaySampler sampler;
  private final BenchmarkService benchmark;
  private final AccountPerformance performance;
  private final MarketDataService marketData;
  private final JdbcClient jdbc;
  private final TimeSource time;
  /** Extra benchmarks' prices at run openings (§16.6), by {@code symbol@instant}: kept once found. */
  private final Map<String, BigDecimal> openingPrices = new ConcurrentHashMap<>();
  /** Openings whose price could not be read yet: the next attempt, and whether the bars answered without a trade. */
  private final Map<String, Miss> openingMisses = new ConcurrentHashMap<>();

  private record Miss(Instant retryAt, boolean answered) {}

  public PnlAnalysisService(PortfolioRepository repository, EodSnapshotRepository snapshots, EodSnapshots eod,
                            RunAnchors anchors, PeriodResolver periods, PnlCalendar calendar,
                            ValuationService valuation, IntradaySampler sampler, BenchmarkService benchmark,
                            AccountPerformance performance, MarketDataService marketData, JdbcClient jdbc,
                            TimeSource time) {
    this.repository = repository;
    this.snapshots = snapshots;
    this.eod = eod;
    this.anchors = anchors;
    this.periods = periods;
    this.calendar = calendar;
    this.valuation = valuation;
    this.sampler = sampler;
    this.benchmark = benchmark;
    this.performance = performance;
    this.marketData = marketData;
    this.jdbc = jdbc;
    this.time = time;
  }

  /** The run's end: live for the active run, its final values for a closed one. */
  private record End(Instant at, BigDecimal equity, BigDecimal netContributions, UnitPoint unit, Valuation live) {

    BigDecimal pnlSinceOpen() {
      return equity.subtract(netContributions);
    }
  }

  // ------------------------------------------------------------------------------------------- analysis

  @Override
  public PnlAnalysisDto analysis(long accountId, HistoryRange range) {
    AccountRow run = run(accountId);
    HistoryRange r = range == null ? HistoryRange.M1 : range;
    Instant now = time.now();
    List<TransferRow> transfers = repository.appliedTransfers(run.id());
    Window window = periods.window(r, calendar.currentPnlDay(now));
    Anchor anchor = window.sinceOpening() ? RunAnchors.opening(run, transfers)
        : anchors.forRun(run, window.anchorDate());
    End end = end(run);
    BigDecimal base = anchor.pnlBase();
    UnitPoint a = anchor.unitPoint();

    List<EarningsPointDto> points = new ArrayList<>();
    points.add(new EarningsPointDto(anchor.at(), MetricsCalculator.twr(a, a), Money.ZERO));
    if (r == HistoryRange.D1) {
      for (Sample s : sampler.samples(run.id(), anchor.at(), end.at())) {
        if (s.sampledAt().isBefore(end.at())) {
          BigDecimal nc = PortfolioHistoryService.netContributionsAt(transfers, s.sampledAt());
          points.add(new EarningsPointDto(s.sampledAt(), MetricsCalculator.twr(a,
              new UnitPoint(s.unitValue(), s.unitSeries())), s.equity().subtract(nc).subtract(base)));
        }
      }
    } else {
      for (EodSnapshot s : snapshots.series(List.of(run.id()), Et.date(anchor.at()).minusDays(1),
          Et.date(end.at()))) {
        if (s.asOf().isAfter(anchor.at()) && s.asOf().isBefore(end.at())) {
          points.add(new EarningsPointDto(s.asOf(), MetricsCalculator.twr(a,
              new UnitPoint(s.unitValue(), s.unitSeries())), s.pnlSinceOpen().subtract(base)));
        }
      }
    }
    BigDecimal pnl = end.pnlSinceOpen().subtract(base);
    BigDecimal ret = MetricsCalculator.twr(a, end.unit());
    points.add(new EarningsPointDto(end.at(), ret, pnl));

    List<BenchmarkCurveDto> benchmarks = new ArrayList<>();
    for (String symbol : benchmark.symbols()) {
      benchmarks.add(benchmarkCurve(symbol, run, anchor, transfers, end.at(), r == HistoryRange.D1));
    }
    BySymbol split = bySymbol(run, anchor, end, transfers);
    return new PnlAnalysisDto(r, anchor.at(), end.at(), ret, pnl, points, benchmarks, split.rows(), split.other());
  }

  /** One benchmark over the window (§16.10): the anchor, each EOD in it (not for 1D), and the live value. */
  BenchmarkCurveDto benchmarkCurve(String symbol, AccountRow run, Anchor anchor, List<TransferRow> transfers,
                                   Instant endAt, boolean intraday) {
    BigDecimal start = startIndex(symbol, anchor, transfers);
    List<RacePointDto> points = new ArrayList<>();
    if (start == null || start.signum() <= 0) {
      return new BenchmarkCurveDto(symbol, null, points);
    }
    points.add(new RacePointDto(anchor.at(), MetricsCalculator.ratioMinusOne(start, start)));
    BigDecimal last = start;
    if (!intraday) {
      LocalDate from = anchor.opening() ? Et.date(anchor.at()).minusDays(1) : anchor.date();
      for (BenchmarkDay d : benchmark.between(symbol, from, Et.date(endAt))) {
        Instant t = calendar.cutoff(d.tradeDate());
        if (t.isAfter(anchor.at()) && t.isBefore(endAt)) {
          points.add(new RacePointDto(t, MetricsCalculator.ratioMinusOne(d.indexValue(), start)));
          last = d.indexValue();
        }
      }
    }
    BigDecimal live = run.active() ? benchmark.liveIndex(symbol).orElse(null) : null;
    if (live != null && endAt.isAfter(anchor.at())) {
      points.add(new RacePointDto(endAt, MetricsCalculator.ratioMinusOne(live, start)));
      last = live;
    }
    return new BenchmarkCurveDto(symbol, points.size() > 1 ? MetricsCalculator.ratioMinusOne(last, start) : null,
        points);
  }

  /**
   * B at the anchor: the EOD row of the anchor day; at an opening, SPY from the mark recorded on the INITIAL transfer
   * (§16.6), an extra benchmark from its price at the opening ({@link #extraIndexAtOpening}), so every line starts at
   * the same instant.
   */
  private BigDecimal startIndex(String symbol, Anchor anchor, List<TransferRow> transfers) {
    if (!anchor.opening()) {
      return benchmark.indexAtEod(symbol, anchor.date()).orElse(null);
    }
    if (!symbol.equals(benchmark.symbol())) {
      return extraIndexAtOpening(symbol, anchor.at());
    }
    for (TransferRow t : transfers) {
      if (Long.valueOf(t.id()).equals(anchor.openingTransferId())) {
        BigDecimal b = benchmark.indexAt(symbol, t.effectiveAt(), t.spyMark()).orElse(null);
        if (b != null) {
          return b;
        }
      }
    }
    return benchmark.indexAtLastEod(symbol, anchor.at()).orElse(null);
  }

  /** The regular open (ET): before it, an extra benchmark has not moved since its last EOD. */
  static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
  /** How long an opening whose price could not be read waits before the next attempt. */
  static final Duration OPENING_MISS_RETRY = Duration.ofMinutes(5);
  private static final int MAX_CACHED_OPENINGS = 10_000;
  /** Enough 1-minute bars for one day (a regular session has 390). */
  private static final int DAY_BARS = 1000;

  /**
   * An extra benchmark (QQQ) at a run's opening (§16.6): no transfer records its mark, so a run opened during a
   * trading day reads the benchmark's price then from its 1-minute bars (the close of the last bar that started
   * before the opening; one bar request per opening, then cached), {@code B = B_prevEOD × P(t) / P_prevEOD} like SPY.
   * An opening on a non-trading day or after the day's cut-off starts at the last EOD (the price has not moved
   * since), and so does a pre-market opening before the day's first trade. After the regular open a bar is
   * required: while the bars are unavailable (or lag) the extra benchmark has no curve, never one from the previous
   * close that would count the move before the opening.
   */
  BigDecimal extraIndexAtOpening(String symbol, Instant t) {
    LocalDate day = Et.date(t);
    if (!calendar.isTradingDay(day) || !t.isBefore(calendar.cutoff(day))) {
      return benchmark.indexAtLastEod(symbol, t).orElse(null);
    }
    Optional<Optional<BigDecimal>> price = priceAt(symbol, t);
    if (price.isEmpty()) {
      return null;                                                  // not known yet: retried later
    }
    if (price.get().isEmpty()) {
      // the bars answered with no trade that day before t
      return Et.time(t).isBefore(REGULAR_OPEN) ? benchmark.indexAtLastEod(symbol, t).orElse(null) : null;
    }
    return benchmark.indexAt(symbol, t, price.get().get()).orElse(null);
  }

  /**
   * The close of the last 1-minute bar of {@code symbol} that started on {@code t}'s day before {@code t}: empty
   * when the request failed or is waiting for its retry, {@code Optional.of(Optional.empty())} when the bars answered
   * without one. Found prices are cached; misses are retried after {@link #OPENING_MISS_RETRY}.
   */
  private Optional<Optional<BigDecimal>> priceAt(String symbol, Instant t) {
    String key = symbol + "@" + t;
    BigDecimal hit = openingPrices.get(key);
    if (hit != null) {
      return Optional.of(Optional.of(hit));
    }
    Instant now = time.now();
    Miss miss = openingMisses.get(key);
    if (miss != null && now.isBefore(miss.retryAt())) {
      return miss.answered() ? Optional.of(Optional.empty()) : Optional.empty();
    }
    Optional<Optional<BigDecimal>> price;
    try {
      BarSeries series = marketData.bars(new BarRequest(symbol, null, Timeframe.MIN1, Et.startOfDay(Et.date(t)), t,
          DAY_BARS, AdjustmentMode.RAW, false));
      price = Optional.of(series.bars().stream()
          .filter(b -> b.start() != null && b.start().isBefore(t) && b.close() != null && b.close().signum() > 0)
          .max(Comparator.comparing(Bar::start)).map(Bar::close));
    } catch (RuntimeException e) {
      price = Optional.empty();                                     // unavailable now: no curve, retried later
    }
    if (price.isPresent() && price.get().isPresent()) {
      if (openingPrices.size() >= MAX_CACHED_OPENINGS) {
        openingPrices.clear();
      }
      openingPrices.put(key, price.get().get());
      openingMisses.remove(key);
    } else {
      if (openingMisses.size() >= MAX_CACHED_OPENINGS) {
        openingMisses.clear();
      }
      openingMisses.put(key, new Miss(now.plus(OPENING_MISS_RETRY), price.isPresent()));
    }
    return price;
  }

  // ------------------------------------------------------------------------------------------- by symbol

  /** P&amp;L by symbol plus the P&amp;L that belongs to no symbol (corrections). */
  record BySymbol(List<SymbolPnlDto> rows, BigDecimal other) {}

  private static final class Acc {
    BigDecimal mvA = Money.ZERO;
    BigDecimal mvB = Money.ZERO;
    BigDecimal net = Money.ZERO;
    BigDecimal sells = Money.ZERO;
    BigDecimal divPaid = Money.ZERO;
    BigDecimal recvA = Money.ZERO;
    BigDecimal recvB = Money.ZERO;
    BigDecimal cil = Money.ZERO;
    BigDecimal caCost = Money.ZERO;
    boolean held;
  }

  BySymbol bySymbol(AccountRow run, Anchor anchor, End end, List<TransferRow> transfers) {
    // an opening anchor precedes everything of its run, even what shares its instant (the run's first fill)
    Instant a = anchor.opening() ? Instant.EPOCH : anchor.at();
    Instant b = end.at();
    Renames renames = renames(run.id(), b);
    Map<String, Acc> acc = new TreeMap<>();
    if (!anchor.opening()) {
      eod.withHoldings(run, anchor.date()).ifPresent(s -> {
        for (HoldingRow h : s.holdings()) {
          Acc x = at(acc, renames.current(h.symbol(), a));
          x.mvA = x.mvA.add(h.marketValue());
        }
      });
    }
    if (end.live() != null) {
      for (PositionRow p : repository.positions(run.id())) {
        Mark m = end.live().marks().get(p.symbol());
        BigDecimal mv = m != null ? Rounding.marketValue(p.symbol(), p.qty(), m.price()) : p.costBasis();
        Acc x = at(acc, p.symbol());
        x.mvB = x.mvB.add(mv);
        x.held = true;
      }
    }
    jdbc.sql("""
            SELECT symbol, executed_at, net_amount,
                   CASE WHEN side = 'SELL' AND realized_pnl IS NOT NULL THEN realized_pnl ELSE 0 END AS realized
              FROM execution WHERE account_id = ? AND executed_at > ? AND executed_at <= ?""")
        .params(run.id(), Jdbc.ts(a), Jdbc.ts(b))
        .query(rs -> {
          Acc x = at(acc, renames.current(rs.getString("symbol"), Jdbc.instant(rs, "executed_at")));
          x.net = x.net.add(money(rs.getBigDecimal("net_amount")));
          x.sells = x.sells.add(money(rs.getBigDecimal("realized")));
        });
    jdbc.sql("""
            SELECT d.symbol, d.accrued_at, l.amount
              FROM cash_ledger l JOIN dividend_entitlement d ON d.id = l.dividend_id
             WHERE l.account_id = ? AND l.entry_type = 'DIVIDEND' AND l.posted_at > ? AND l.posted_at <= ?""")
        .params(run.id(), Jdbc.ts(a), Jdbc.ts(b))
        .query(rs -> {
          Acc x = at(acc, renames.current(rs.getString("symbol"), Jdbc.instant(rs, "accrued_at")));
          x.divPaid = x.divPaid.add(money(rs.getBigDecimal("amount")));
        });
    receivables(run.id(), a, (symbol, amount) -> {
      Acc x = at(acc, renames.current(symbol.symbol(), symbol.at()));
      x.recvA = x.recvA.add(amount);
    });
    receivables(run.id(), b, (symbol, amount) -> {
      Acc x = at(acc, renames.current(symbol.symbol(), symbol.at()));
      x.recvB = x.recvB.add(amount);
    });
    jdbc.sql("""
            SELECT symbol, posted_at, amount FROM cash_ledger
             WHERE account_id = ? AND entry_type = 'CASH_IN_LIEU' AND posted_at > ? AND posted_at <= ?""")
        .params(run.id(), Jdbc.ts(a), Jdbc.ts(b))
        .query(rs -> {
          String s = rs.getString("symbol");
          if (s != null) {
            Acc x = at(acc, renames.current(s, Jdbc.instant(rs, "posted_at")));
            x.cil = x.cil.add(money(rs.getBigDecimal("amount")));
          }
        });
    // corporate actions realize "cash received − cost relieved" (§14 cash in lieu); renames move cost unchanged
    jdbc.sql("""
            SELECT symbol_after, effective_at, cost_after - cost_before AS delta FROM position_adjustment
             WHERE account_id = ? AND effective_at > ? AND effective_at <= ?""")
        .params(run.id(), Jdbc.ts(a), Jdbc.ts(b))
        .query(rs -> {
          Acc x = at(acc, renames.current(rs.getString("symbol_after"), Jdbc.instant(rs, "effective_at")));
          x.caCost = x.caCost.add(money(rs.getBigDecimal("delta")));
        });

    List<SymbolPnlDto> rows = new ArrayList<>();
    acc.forEach((symbol, x) -> {
      BigDecimal dividends = x.divPaid.add(x.recvB).subtract(x.recvA);
      BigDecimal total = x.mvB.subtract(x.mvA).add(x.net).add(dividends).add(x.cil);
      BigDecimal realized = x.sells.add(x.cil).add(x.caCost);
      BigDecimal unrealized = total.subtract(realized).subtract(dividends);
      boolean any = x.held || x.mvA.signum() != 0 || x.net.signum() != 0 || dividends.signum() != 0
          || x.cil.signum() != 0 || total.signum() != 0 || realized.signum() != 0;
      if (any) {
        rows.add(new SymbolPnlDto(symbol, realized, unrealized, dividends, total, x.held));
      }
    });
    rows.sort(Comparator.comparing(SymbolPnlDto::total).reversed().thenComparing(SymbolPnlDto::symbol));
    BigDecimal other = Money.ZERO;
    for (TransferRow t : transfers) {
      boolean after = anchor.opening() ? !Long.valueOf(t.id()).equals(anchor.openingTransferId())
          : t.effectiveAt().isAfter(a);
      if (after && !t.effectiveAt().isAfter(b) && !t.contribution() && t.type() == TransferType.ADJUSTMENT) {
        other = other.add(t.amount());
      }
    }
    return new BySymbol(List.copyOf(rows), other);
  }

  private static Acc at(Map<String, Acc> acc, String symbol) {
    return acc.computeIfAbsent(symbol, k -> new Acc());
  }

  /** One NAME_CHANGE of the account's position (§14): {@code before} became {@code after} at {@code at}. */
  record Rename(String before, String after, Instant at) {}

  /**
   * The account's renames up to the window's end, oldest first: a symbol seen at {@code t} (a holding, a fill, an
   * entitlement's accrual) is reported under the name the renames after {@code t} gave it (§16.4), so a ticker that is
   * later reused by another company stays apart.
   */
  record Renames(List<Rename> list) {

    String current(String symbol, Instant t) {
      String s = symbol;
      int hops = 0;
      for (Rename r : list) {
        if (r.at().isAfter(t) && r.before().equals(s) && hops++ < MAX_RENAME_HOPS) {
          s = r.after();
        }
      }
      return s;
    }
  }

  private Renames renames(long accountId, Instant b) {
    return new Renames(jdbc.sql("""
            SELECT DISTINCT symbol_before, symbol_after, effective_at FROM position_adjustment
             WHERE account_id = ? AND effective_at <= ? AND symbol_before <> symbol_after
             ORDER BY effective_at""")
        .params(accountId, Jdbc.ts(b))
        .query((rs, n) -> new Rename(rs.getString("symbol_before"), rs.getString("symbol_after"),
            Jdbc.instant(rs, "effective_at")))
        .list());
  }

  /** A dividend entitlement's symbol and accrual instant. */
  record EntitlementSymbol(String symbol, Instant at) {}

  /** §16.1 point-in-time dividend receivable at {@code t}, per entitlement. */
  private void receivables(long accountId, Instant t, BiConsumer<EntitlementSymbol, BigDecimal> sink) {
    jdbc.sql("""
            SELECT symbol, accrued_at, amount FROM dividend_entitlement
             WHERE account_id = ? AND accrued_at <= ?
               AND (paid_at IS NULL OR paid_at > ?) AND (voided_at IS NULL OR voided_at > ?)""")
        .params(accountId, Jdbc.ts(t), Jdbc.ts(t), Jdbc.ts(t))
        .query(rs -> {
          sink.accept(new EntitlementSymbol(rs.getString("symbol"), Jdbc.instant(rs, "accrued_at")),
              money(rs.getBigDecimal("amount")));
        });
  }

  // ------------------------------------------------------------------------------------------- calendar

  /** One day of the run: its P&amp;L and flows, and U before and after it. */
  record DayCell(LocalDate date, BigDecimal pnl, BigDecimal flows, UnitPoint before, UnitPoint after, boolean live) {}

  @Override
  public PnlCalendarDto calendar(long accountId, CalendarView view, YearMonth month, Integer year) {
    AccountRow run = run(accountId);
    CalendarView v = view == null ? CalendarView.DAY : view;
    Instant now = time.now();
    LocalDate today = Et.date(now);
    LocalDate from;
    LocalDate to;
    if (v == CalendarView.DAY) {
      YearMonth ym = month != null ? month : YearMonth.from(today);
      from = ym.atDay(1);
      to = ym.atEndOfMonth();
    } else {
      int y = year != null ? year : today.getYear();
      from = LocalDate.of(y, 1, 1);
      to = LocalDate.of(y, 12, 31);
    }
    List<DayCell> days = days(run, now).stream()
        .filter(d -> !d.date().isBefore(from) && !d.date().isAfter(to)).toList();
    List<PnlCalendarCellDto> cells = new ArrayList<>();
    if (v == CalendarView.DAY) {
      for (DayCell d : days) {
        cells.add(new PnlCalendarCellDto(d.date(), d.pnl(), MetricsCalculator.twr(d.before(), d.after()), d.flows(),
            d.live()));
      }
    } else {
      Map<YearMonth, List<DayCell>> byMonth = new TreeMap<>();
      days.forEach(d -> byMonth.computeIfAbsent(YearMonth.from(d.date()), k -> new ArrayList<>()).add(d));
      byMonth.forEach((ym, list) -> cells.add(merge(ym.atDay(1), list)));
    }
    PnlCalendarCellDto all = days.isEmpty() ? null : merge(from, days);
    PnlCalendarTotalDto total = all == null ? new PnlCalendarTotalDto(Money.ZERO, null)
        : new PnlCalendarTotalDto(all.pnl(), all.ret());
    return new PnlCalendarDto(v, from, to, now, cells, total);
  }

  /** Several consecutive days as one cell: Σ P&amp;L and flows, {@code U_last / U_beforeFirst − 1}. */
  static PnlCalendarCellDto merge(LocalDate date, List<DayCell> days) {
    BigDecimal pnl = Money.ZERO;
    BigDecimal flows = Money.ZERO;
    boolean live = false;
    for (DayCell d : days) {
      pnl = pnl.add(d.pnl());
      flows = flows.add(d.flows());
      live |= d.live();
    }
    return new PnlCalendarCellDto(date, pnl, MetricsCalculator.twr(days.get(0).before(),
        days.get(days.size() - 1).after()), flows, live);
  }

  /**
   * Every day of the run, oldest first: one per EOD snapshot ({@code pnl_day}, {@code flows_in_day} without the
   * run's own INITIAL funding), then today's live day (§16.2 day P&amp;L) if its snapshot does not exist yet.
   */
  List<DayCell> days(AccountRow run, Instant now) {
    List<TransferRow> transfers = repository.appliedTransfers(run.id());
    Anchor opening = RunAnchors.opening(run, transfers);
    BigDecimal initial = transfers.isEmpty() ? Money.ZERO : transfers.get(0).amount();
    List<DayCell> out = new ArrayList<>();
    UnitPoint prev = opening.unitPoint();
    Set<LocalDate> seen = new TreeSet<>();
    for (EodSnapshot s : snapshots.series(List.of(run.id()), Et.date(run.openedAt()).minusDays(1),
        Et.date(now).plusDays(1))) {
      if (s.asOf().isAfter(now)) {
        continue;
      }
      UnitPoint cur = new UnitPoint(s.unitValue(), s.unitSeries());
      BigDecimal flows = s.flowsInDay();
      Instant prevCutoff = calendar.cutoff(calendar.previousTradingDay(s.tradeDate()));
      if (opening.at().isAfter(prevCutoff) && !opening.at().isAfter(s.asOf())) {
        flows = flows.subtract(initial);               // the run's own funding is its anchor, not a deposit
      }
      out.add(new DayCell(s.tradeDate(), s.pnlDay(), flows, prev, cur, false));
      seen.add(s.tradeDate());
      prev = cur;
    }
    LocalDate d = calendar.currentPnlDay(now);
    if (run.active() && !seen.contains(d) && !run.openedAt().isAfter(calendar.cutoff(d))) {
      AccountPnl live = performance.accountPnl(run.id());
      Anchor dayAnchor = anchors.forRun(run, performance.todayAnchorDate());
      BigDecimal flows = RunAnchors.flowsAfter(dayAnchor, transfers, live.asOf());
      UnitPoint before = dayAnchor.unitPoint();
      Valuation v = valuation.valueNow(run.id());
      out.add(new DayCell(d, live.dayPnl(), flows, before, new UnitPoint(v.unitValue(), v.unitSeries()), true));
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------- helpers

  private AccountRow run(long accountId) {
    return repository.account(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + accountId + " does not exist."));
  }

  private End end(AccountRow run) {
    if (!run.active() && run.finalEquity() != null && run.finalUnitValue() != null) {
      return new End(run.closedAt() != null ? run.closedAt() : time.now(), run.finalEquity(), run.netContributions(),
          new UnitPoint(run.finalUnitValue(), run.unitSeries()), null);
    }
    Valuation v = valuation.valueNow(run.id());
    return new End(v.asOf(), v.equity(), v.netContributions(), new UnitPoint(v.unitValue(), v.unitSeries()), v);
  }

  private static BigDecimal money(BigDecimal v) {
    return v == null ? Money.ZERO : Money.cents(v);
  }
}
