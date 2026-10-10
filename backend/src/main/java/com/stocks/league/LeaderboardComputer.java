package com.stocks.league;

import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.money.Money;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.league.LeagueRepository.Activity;
import com.stocks.league.LeagueRepository.SeriesPoint;
import com.stocks.portfolio.BenchmarkService;
import com.stocks.portfolio.EodSnapshot;
import com.stocks.portfolio.EodSnapshot.HoldingRow;
import com.stocks.portfolio.EodSnapshotRepository;
import com.stocks.portfolio.EodSnapshots;
import com.stocks.portfolio.LeagueSettings;
import com.stocks.portfolio.MetricsCalculator;
import com.stocks.portfolio.MetricsCalculator.UnitPoint;
import com.stocks.portfolio.PeriodResolver;
import com.stocks.portfolio.PeriodResolver.Window;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.PnlCalendar;
import com.stocks.portfolio.PortfolioRepository;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PortfolioRepository.TransferRow;
import com.stocks.portfolio.RunAnchors;
import com.stocks.portfolio.RunAnchors.Anchor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Computes every period of the leaderboard for one end instant (§16.5): live (end = now, live valuations) every 5 s,
 * or as of an EOD cut-off (end = that day's snapshots) for {@code leaderboard_snapshot}.
 *
 * <p>Per player and period, with anchor {@code a} (§16.3) and end {@code b}:
 * <ul>
 *   <li><b>Series across runs.</b> The anchor is the EOD snapshot of whichever run was open at {@code a}; if the
 *       player's first run opened after {@code a}, its opening ({@code partialPeriod}). A reset continues the unit
 *       series, so a reset never erases a loss.</li>
 *   <li>{@code TWR = U(b) / U(a) − 1} on one unit series, else −100% and {@code rebased}. Deposits never move it.</li>
 *   <li>{@code PnL = Σ_runs [E − NC] at min(b, close) − [E − NC] at max(a, open)}: net-contribution P&amp;L.</li>
 *   <li>Excess vs SPY total return, max drawdown and the sparkline over the EOD unit values in the window plus the
 *       end value; fills, symbols, win rate and API fills from {@link LeagueRepository#activity}.</li>
 * </ul>
 */
@Component
public class LeaderboardComputer {

  /** Up to 30 daily unit values plus the end value (§16.5 {@code spark}). */
  static final int SPARK_DAYS = 30;

  /** The end state of the run open at {@code b}. */
  public record End(long accountId, Instant at, BigDecimal equity, BigDecimal netContributions, BigDecimal unitValue,
                    int unitSeries) {}

  /** One period's entries (unsorted) and its common facts. */
  public record PeriodBoard(LeaderboardPeriod period, Window window, Instant periodStart, BigDecimal benchmarkReturn,
                            List<LeagueEntry> entries) {}

  /** Every period, computed for one end instant. */
  public record Board(Instant asOf, LocalDate pnlDay, Map<LeaderboardPeriod, PeriodBoard> periods) {}

  private final PlayerDirectory players;
  private final PortfolioRepository accounts;
  private final LeagueRepository league;
  private final EodSnapshots anchors;
  private final EodSnapshotRepository snapshots;
  private final PeriodResolver periods;
  private final PnlCalendar calendar;
  private final BenchmarkService benchmark;
  private final LeagueSettings settings;
  private final ValuationService valuation;
  private final TimeSource time;

  public LeaderboardComputer(PlayerDirectory players, PortfolioRepository accounts, LeagueRepository league,
                             EodSnapshots anchors, EodSnapshotRepository snapshots, PeriodResolver periods,
                             PnlCalendar calendar, BenchmarkService benchmark, LeagueSettings settings,
                             ValuationService valuation, TimeSource time) {
    this.players = players;
    this.accounts = accounts;
    this.league = league;
    this.anchors = anchors;
    this.snapshots = snapshots;
    this.periods = periods;
    this.calendar = calendar;
    this.benchmark = benchmark;
    this.settings = settings;
    this.valuation = valuation;
    this.time = time;
  }

  /** The live board: end = now, valued live. */
  public Board live() {
    Instant now = time.now();
    Map<Long, End> ends = new TreeMap<>();
    for (Valuation v : valuation.valueAllActive().values()) {
      ends.put(v.accountId(), new End(v.accountId(), v.asOf(), v.equity(), v.netContributions(), v.unitValue(),
          v.unitSeries()));
    }
    return compute(now, calendar.currentPnlDay(now), ends, benchmark.liveIndex().orElse(null));
  }

  /** The board as of trading day D's cut-off, from D's EOD snapshots (§16.4 step 4). */
  public Board endOfDay(LocalDate tradeDate) {
    Instant cutoff = calendar.cutoff(tradeDate);
    Map<Long, End> ends = new TreeMap<>();
    snapshots.byDate(tradeDate).forEach((account, s) -> ends.put(account, new End(account, s.asOf(), s.equity(),
        s.netContributions(), s.unitValue(), s.unitSeries())));
    return compute(cutoff, tradeDate, ends, benchmark.indexAtEod(tradeDate).orElse(null));
  }

  /**
   * @param b        the end instant
   * @param pnlDay   P&amp;L day D of {@code b} (anchors are relative to it)
   * @param ends     end state per account id (the runs open at {@code b})
   * @param benchEnd B at {@code b} (null when unknown)
   */
  Board compute(Instant b, LocalDate pnlDay, Map<Long, End> ends, BigDecimal benchEnd) {
    Map<Long, PlayerRow> playerRows = players.all();
    Map<Long, List<AccountRow>> runsByPlayer = accounts.runsByPlayer();
    Map<Long, List<TransferRow>> transfers = league.appliedTransfersByAccount();
    Map<Long, List<SeriesPoint>> series = league.eodSeries();
    int minFills = settings.minFills();
    int minSymbols = settings.minSymbols();
    LocalDate todayAnchor = periods.anchorDate(LeaderboardPeriod.TODAY, pnlDay);
    Map<Long, String> topHoldings = league.topHoldings(todayAnchor);

    // per player: the runs that existed at b, the run open at b and its end state, and the first opening
    List<PlayerState> states = new ArrayList<>();
    for (PlayerRow p : playerRows.values()) {
      if (!p.listed()) {
        continue;
      }
      List<AccountRow> runs = runsByPlayer.getOrDefault(p.id(), List.of()).stream()
          .filter(r -> !r.openedAt().isAfter(b)).toList();
      AccountRow endRun = runs.stream().filter(r -> ends.containsKey(r.id()) && r.openAt(b)).findFirst()
          .orElse(null);
      if (endRun == null) {
        continue;
      }
      Anchor firstOpening = RunAnchors.opening(runs.get(0), transfers.getOrDefault(runs.get(0).id(), List.of()));
      states.add(new PlayerState(p, runs, endRun, ends.get(endRun.id()), firstOpening));
    }

    Map<LeaderboardPeriod, PeriodBoard> out = new EnumMap<>(LeaderboardPeriod.class);
    for (LeaderboardPeriod period : LeaderboardPeriod.values()) {
      Window window = periods.window(period, pnlDay);
      Map<Long, Activity> activity = league.activity(window.anchorAt(), b);
      Map<Long, Integer> previousRanks = league.previousRanks(period, pnlDay);
      Map<Long, EodSnapshot> anchorRows = window.sinceOpening() ? Map.of() : snapshots.byDate(window.anchorDate());
      BigDecimal benchAnchor = window.sinceOpening() ? null
          : benchmark.indexAtEod(window.anchorDate()).orElse(null);
      List<LeagueEntry> entries = new ArrayList<>();
      Instant earliestOpening = null;
      for (PlayerState s : states) {
        LeagueEntry e = entry(period, window, s, b, transfers, series, anchorRows, benchAnchor, benchEnd,
            activity.getOrDefault(s.player.id(), Activity.NONE), minFills, minSymbols,
            topHolding(s.endRun, todayAnchor, topHoldings), previousRanks.get(s.player.id()));
        entries.add(e);
        if (earliestOpening == null || s.firstOpening.at().isBefore(earliestOpening)) {
          earliestOpening = s.firstOpening.at();
        }
      }
      Instant periodStart = window.sinceOpening() ? (earliestOpening != null ? earliestOpening : b)
          : window.anchorAt();
      BigDecimal benchStart = window.sinceOpening() ? leagueOpeningBenchmark(states, transfers) : benchAnchor;
      out.put(period, new PeriodBoard(period, window, periodStart,
          MetricsCalculator.ratioMinusOne(benchEnd, benchStart), List.copyOf(entries)));
    }
    return new Board(b, pnlDay, out);
  }

  private record PlayerState(PlayerRow player, List<AccountRow> runs, AccountRow endRun, End end,
                             Anchor firstOpening) {}

  private LeagueEntry entry(LeaderboardPeriod period, Window window, PlayerState s, Instant b,
                            Map<Long, List<TransferRow>> transfers, Map<Long, List<SeriesPoint>> series,
                            Map<Long, EodSnapshot> anchorRows, BigDecimal benchAnchor, BigDecimal benchEnd,
                            Activity activity, int minFills, int minSymbols, String topHolding, Integer prevRank) {
    // ---- the anchor: snapshot of the run open at a, else the opening of the first run opened after a
    AccountRow anchorRun = null;
    Anchor anchor = null;
    boolean partial = false;
    if (window.sinceOpening()) {
      anchorRun = s.runs.get(0);
      anchor = s.firstOpening;
    } else {
      for (AccountRow r : s.runs) {
        if (r.openAt(window.anchorAt())) {
          anchorRun = r;
          EodSnapshot snap = anchorRows.get(r.id());
          if (snap == null) {
            snap = anchors.get(r, window.anchorDate()).orElse(null);
          }
          anchor = snap != null ? RunAnchors.fromSnapshot(snap)
              : RunAnchors.opening(r, transfers.getOrDefault(r.id(), List.of()));
          break;
        }
      }
      if (anchorRun == null) {
        for (AccountRow r : s.runs) {
          if (r.openedAt().isAfter(window.anchorAt())) {
            anchorRun = r;
            anchor = RunAnchors.opening(r, transfers.getOrDefault(r.id(), List.of()));
            partial = r == s.runs.get(0);
            break;
          }
        }
      }
      if (anchorRun == null) {                       // cannot happen: the end run opened at or before b
        anchorRun = s.endRun;
        anchor = RunAnchors.opening(s.endRun, transfers.getOrDefault(s.endRun.id(), List.of()));
      }
    }
    UnitPoint start = anchor.unitPoint();
    UnitPoint end = new UnitPoint(s.end.unitValue(), s.end.unitSeries());
    BigDecimal twr = MetricsCalculator.twr(start, end);
    boolean rebased = start.unitSeries() != end.unitSeries();

    // ---- net-contribution P&L across the runs in the window, and the flows in it
    BigDecimal pnl = Money.ZERO;
    BigDecimal flows = Money.ZERO;
    for (AccountRow r : s.runs) {
      if (r.runNo() < anchorRun.runNo()) {
        continue;
      }
      BigDecimal endValue;
      if (r.id() == s.endRun.id()) {
        endValue = s.end.equity().subtract(s.end.netContributions());
      } else {
        BigDecimal finalEquity = r.finalEquity() != null ? r.finalEquity() : r.netContributions();
        endValue = finalEquity.subtract(r.netContributions());
      }
      BigDecimal startValue = r.id() == anchorRun.id() ? anchor.pnlBase() : Money.ZERO;
      pnl = pnl.add(endValue.subtract(startValue));
      List<TransferRow> rt = transfers.getOrDefault(r.id(), List.of());
      if (r.id() == anchorRun.id()) {
        flows = flows.add(RunAnchors.flowsAfter(anchor, rt, b));
      } else {
        for (TransferRow t : rt) {
          if (t.contribution() && !t.effectiveAt().isAfter(b)) {
            flows = flows.add(t.amount());
          }
        }
      }
      if (r.id() == s.endRun.id()) {
        break;
      }
    }

    // ---- EOD unit values in the window (one per day), for the drawdown and the sparkline
    TreeMap<LocalDate, SeriesPoint> daily = new TreeMap<>();
    for (AccountRow r : s.runs) {
      for (SeriesPoint p : series.getOrDefault(r.id(), List.of())) {
        if (p.asOf().isAfter(anchor.at()) && !p.asOf().isAfter(b) && r.openAt(p.asOf())) {
          daily.put(p.tradeDate(), p);
        }
      }
    }
    List<UnitPoint> path = new ArrayList<>();
    path.add(start);
    daily.values().forEach(p -> path.add(new UnitPoint(p.unitValue(), p.unitSeries())));
    path.add(end);
    BigDecimal mdd = MetricsCalculator.maxDrawdown(path);
    List<BigDecimal> spark = new ArrayList<>();
    List<SeriesPoint> lastDays = new ArrayList<>(daily.values());
    for (SeriesPoint p : lastDays.subList(Math.max(0, lastDays.size() - SPARK_DAYS), lastDays.size())) {
      spark.add(p.unitValue());
    }
    spark.add(s.end.unitValue());

    // ---- benchmark
    BigDecimal benchStart = anchor.opening() ? openingBenchmark(anchor, transfers.get(anchorRun.id())) : benchAnchor;
    BigDecimal excess = MetricsCalculator.excess(twr, benchStart, benchEnd);

    boolean eligible = Eligibility.eligible(period, activity.fills(), activity.symbols(), minFills, minSymbols);
    String note = Eligibility.note(period, activity.fills(), activity.symbols(), minFills, minSymbols);
    boolean apiTrader = s.player.kind() == PlayerKind.HUMAN && activity.apiFills() > 0;
    return new LeagueEntry(s.player, s.endRun.id(), twr, pnl, excess, mdd, rebased, partial, s.end.equity(),
        s.end.netContributions(), flows, activity.fills(), activity.symbols(),
        MetricsCalculator.winRate(activity.wins(), activity.closingSells()), topHolding, apiTrader,
        List.copyOf(spark), s.firstOpening.at(), eligible, note, prevRank);
  }

  /** The symbol with the largest previous-EOD market value of the end run. */
  private String topHolding(AccountRow endRun, LocalDate todayAnchor, Map<Long, String> persisted) {
    String top = persisted.get(endRun.id());
    if (top != null || !endRun.openAt(calendar.cutoff(todayAnchor))) {
      return top;
    }
    EodSnapshot computed = anchors.get(endRun, todayAnchor).orElse(null);
    if (computed == null || computed.holdings() == null) {
      return null;
    }
    HoldingRow best = null;
    for (HoldingRow h : computed.holdings()) {
      if (best == null || h.marketValue().compareTo(best.marketValue()) > 0) {
        best = h;
      }
    }
    return best == null ? null : best.symbol();
  }

  /** B at a run's opening, from the SPY mark recorded on its INITIAL transfer (§16.6). */
  private BigDecimal openingBenchmark(Anchor opening, List<TransferRow> runTransfers) {
    if (runTransfers == null) {
      return null;
    }
    for (TransferRow t : runTransfers) {
      if (Long.valueOf(t.id()).equals(opening.openingTransferId())) {
        return benchmark.indexAt(t.effectiveAt(), t.spyMark()).orElse(null);
      }
    }
    return null;
  }

  /** B at the league's first opening (the ALL period's common anchor). */
  private BigDecimal leagueOpeningBenchmark(List<PlayerState> states, Map<Long, List<TransferRow>> transfers) {
    PlayerState first = null;
    for (PlayerState s : states) {
      if (first == null || s.firstOpening.at().isBefore(first.firstOpening.at())) {
        first = s;
      }
    }
    return first == null ? null : openingBenchmark(first.firstOpening, transfers.get(first.runs.get(0).id()));
  }
}
