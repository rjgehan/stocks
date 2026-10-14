package com.stocks.league;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.Et;
import com.stocks.contract.portfolio.LeagueStatsQueries;
import com.stocks.contract.portfolio.dto.BenchmarkCurveDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto.ClosedLotDto;
import com.stocks.contract.portfolio.dto.PlayerStatsDto.RankPointDto;
import com.stocks.contract.portfolio.dto.RaceChartDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RacePointDto;
import com.stocks.contract.portfolio.dto.RaceChartDto.RaceSeriesDto;
import com.stocks.league.LeaderboardComputer.Board;
import com.stocks.league.LeaderboardComputer.PeriodBoard;
import com.stocks.league.LeagueEntry.CurvePoint;
import com.stocks.league.LeagueRepository.Activity;
import com.stocks.league.LeagueRepository.RankRow;
import com.stocks.portfolio.BenchmarkService;
import com.stocks.portfolio.BenchmarkService.BenchmarkDay;
import com.stocks.portfolio.ClosedLots;
import com.stocks.portfolio.ClosedLots.Lot;
import com.stocks.portfolio.ClosedLots.Summary;
import com.stocks.portfolio.IntradaySampler;
import com.stocks.portfolio.IntradaySampler.Sample;
import com.stocks.portfolio.LeagueSettings;
import com.stocks.portfolio.MetricsCalculator;
import com.stocks.portfolio.PlayerDirectory;
import com.stocks.portfolio.PlayerDirectory.PlayerRow;
import com.stocks.portfolio.PnlCalendar;
import com.stocks.portfolio.PortfolioRepository;
import com.stocks.portfolio.PortfolioRepository.AccountRow;
import com.stocks.portfolio.PublicProfileService;
import com.stocks.portfolio.TradeHistoryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * {@link LeagueStatsQueries} (§16.8, §16.9, Phase 2): league stats per player and period, and the race chart. Both read
 * the live leaderboard's cached computation ({@link LeaderboardServiceImpl#current()}), so a profile's return and
 * P&amp;L always equal the player's row and the last point of every race line equals its {@code return}:
 * <ul>
 *   <li>the unit-value metrics (Sharpe, volatility, drawdown) and the profit-factor sums come with every
 *       {@link LeagueEntry} ({@link LeagueEntry.Extras});</li>
 *   <li>the closed lots (hold time, best and worst trade) are a FIFO replay of the player's executions and
 *       adjustments ({@link ClosedLots}), on request; another player's trade statistics (closing sells, wins, win
 *       rate, gross profit and loss, profit factor, closed lots, hold time, best and worst trade) only count what
 *       happened before the feed delay (§16.7), so a profile never shows a sell before the feed does;</li>
 *   <li>rank history: the latest 30 EOD {@code leaderboard_snapshot} ranks of the period.</li>
 * </ul>
 * The race chart normalizes each entry's curve by its anchor ({@code U(t) / U(a) − 1}, −1 across a rebase); TODAY uses
 * the active run's 5-minute samples. Charts are cached per board computation, period and kind.
 */
@Service
public class PlayerStatsService implements LeagueStatsQueries {

  /** Rank history length (§16.8). */
  static final int RANK_HISTORY = 30;

  private record ChartKey(LeaderboardPeriod period, KindFilter kind) {}

  private record ChartCache(Board board, Map<ChartKey, RaceChartDto> charts) {}

  private final LeaderboardServiceImpl leaderboard;
  private final LeagueRepository league;
  private final TradeHistoryRepository trades;
  private final PortfolioRepository accounts;
  private final PublicProfileService profiles;
  private final PlayerDirectory players;
  private final LeagueSettings settings;
  private final IntradaySampler sampler;
  private final BenchmarkService benchmark;
  private final PnlCalendar calendar;
  private volatile ChartCache cache = new ChartCache(null, Map.of());

  public PlayerStatsService(LeaderboardServiceImpl leaderboard, LeagueRepository league, TradeHistoryRepository trades,
                            PortfolioRepository accounts, PublicProfileService profiles, PlayerDirectory players,
                            LeagueSettings settings, IntradaySampler sampler, BenchmarkService benchmark,
                            PnlCalendar calendar) {
    this.leaderboard = leaderboard;
    this.league = league;
    this.trades = trades;
    this.accounts = accounts;
    this.profiles = profiles;
    this.players = players;
    this.settings = settings;
    this.sampler = sampler;
    this.benchmark = benchmark;
    this.calendar = calendar;
  }

  // ------------------------------------------------------------------------------------------- stats (§16.8)

  @Override
  public PlayerStatsDto playerStats(String handle, LeaderboardPeriod period, Long viewerPlayerId) {
    PlayerRow p = profiles.player(handle);
    return stats(p, period, viewerPlayerId != null && viewerPlayerId == p.id());
  }

  @Override
  public PlayerStatsDto myStats(long playerId, LeaderboardPeriod period) {
    PlayerRow p = players.byId(playerId).filter(PlayerRow::listed).orElseThrow(PlayerStatsService::noAccount);
    return stats(p, period, true);
  }

  private PlayerStatsDto stats(PlayerRow p, LeaderboardPeriod period, boolean self) {
    LeaderboardPeriod per = period == null ? LeaderboardPeriod.ALL : period;
    Board board = leaderboard.current();
    PeriodBoard pb = board.periods().get(per);
    LeagueEntry e = pb.entries().stream().filter(x -> x.player().id() == p.id()).findFirst()
        .orElseThrow(PlayerStatsService::noAccount);
    LeaderboardRowDto row = LeaderboardRowMapper.rows(pb.entries(), LeaderboardMetric.RETURN, KindFilter.ALL,
            settings.humanKindExcludesApiTraders()).stream()
        .filter(r -> r.player().playerId().equals(p.publicId())).findFirst().orElse(null);
    LeagueEntry.Extras x = e.extras();
    Instant start = x.curve().isEmpty() ? pb.periodStart() : x.curve().get(0).t();

    // another player's trade statistics are as of the feed delay, if any (§16.7; none by default): a profile never
    // shows a sell, its realized P&L or its hold time before the feed does; the player's own are live
    Instant tradesAsOf = self ? board.asOf() : board.asOf().minus(settings.tradeFeedDelay());
    // closed lots of every run of the player closed in (a, tradesAsOf]
    List<Long> runIds = accounts.runsOfPlayer(p.id()).stream().map(AccountRow::id).toList();
    Instant from = pb.window().sinceOpening() ? null : pb.window().anchorAt();
    List<Lot> lots = trades.closedLots(runIds).stream()
        .filter(l -> (from == null || l.closedAt().isAfter(from)) && !l.closedAt().isAfter(tradesAsOf))
        .toList();
    Summary summary = ClosedLots.summarize(lots, tradesAsOf);
    int closingSells = x.closingSells();
    int wins = x.wins();
    BigDecimal grossProfit = x.grossProfit();
    BigDecimal grossLoss = x.grossLoss();
    BigDecimal profitFactor = x.profitFactor();
    BigDecimal winRate = e.winRate();
    if (tradesAsOf.isBefore(board.asOf())) {
      Activity delayed = league.activityOf(p.id(), pb.window().anchorAt(), tradesAsOf);
      closingSells = delayed.closingSells();
      wins = delayed.wins();
      grossProfit = delayed.grossProfit();
      grossLoss = delayed.grossLoss();
      profitFactor = MetricsCalculator.profitFactor(grossProfit, grossLoss);
      winRate = MetricsCalculator.winRate(wins, closingSells);
    }

    List<RankPointDto> ranks = ranks(league.rankHistories(per, board.pnlDay(), RANK_HISTORY).get(p.id()));
    return new PlayerStatsDto(p.summary(), per, start, board.asOf(), e.twr(), e.pnl(), e.maxDrawdown(),
        e.partialPeriod(), e.rebased(), x.risk().sharpe(), x.risk().volatility(), x.risk().dailyReturns(),
        MetricsCalculator.SHARPE_MIN_DAYS, closingSells, wins, winRate, grossProfit, grossLoss, profitFactor,
        summary.count(), summary.avgHoldSeconds(), tradesAsOf, lot(summary.best()), lot(summary.worst()),
        row == null ? null : row.rank(), row == null ? null : row.rankChange(), ranks);
  }

  static ClosedLotDto lot(Lot l) {
    return l == null ? null : new ClosedLotDto(l.symbol(), l.qty(), l.openedAt(), l.closedAt(), l.holdSeconds(),
        l.cost(), l.proceeds(), l.realizedPnl(), l.ret());
  }

  static List<RankPointDto> ranks(List<RankRow> rows) {
    if (rows == null) {
      return List.of();
    }
    return rows.stream().map(r -> new RankPointDto(r.date(), r.rank(), r.ranked())).toList();
  }

  private static ApiException noAccount() {
    return ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "This player has no active account yet.");
  }

  // ------------------------------------------------------------------------------------------- race chart (§16.9)

  @Override
  public RaceChartDto race(LeaderboardPeriod period, KindFilter kind) {
    LeaderboardPeriod per = period == null ? LeaderboardPeriod.WEEK : period;
    KindFilter k = kind == null ? KindFilter.ALL : kind;
    Board board = leaderboard.current();
    ChartCache c = cache;
    ChartKey key = new ChartKey(per, k);
    if (c.board() == board && c.charts().containsKey(key)) {
      return c.charts().get(key);
    }
    RaceChartDto chart = compute(board, per, k);
    Map<ChartKey, RaceChartDto> charts = c.board() == board ? new ConcurrentHashMap<>(c.charts())
        : new ConcurrentHashMap<>();
    charts.put(key, chart);
    cache = new ChartCache(board, charts);
    return chart;
  }

  RaceChartDto compute(Board board, LeaderboardPeriod per, KindFilter kind) {
    PeriodBoard pb = board.periods().get(per);
    List<LeaderboardRowDto> rows = LeaderboardRowMapper.rows(pb.entries(), LeaderboardMetric.RETURN, kind,
        settings.humanKindExcludesApiTraders());
    Map<String, LeagueEntry> byPublicId = new HashMap<>();
    pb.entries().forEach(e -> byPublicId.put(e.player().publicId(), e));
    Map<Long, List<RankRow>> ranks = league.rankHistories(per, board.pnlDay(), RANK_HISTORY);
    List<RaceSeriesDto> series = new ArrayList<>();
    for (LeaderboardRowDto row : rows) {
      LeagueEntry e = byPublicId.get(row.player().playerId());
      if (e == null) {
        continue;
      }
      List<CurvePoint> curve = per == LeaderboardPeriod.TODAY ? todayCurve(e, board.asOf()) : e.extras().curve();
      series.add(new RaceSeriesDto(row.player(), row.rank(), row.ret(), row.rebased(), row.partialPeriod(),
          normalize(curve), ranks(ranks.get(e.player().id()))));
    }
    return new RaceChartDto(per, kind, pb.periodStart(), board.asOf(), benchmarkCurve(pb, board), series);
  }

  /**
   * TODAY's curve: the anchor (previous EOD, or the opening), the active run's 5-minute samples, then the end point.
   */
  List<CurvePoint> todayCurve(LeagueEntry e, Instant asOf) {
    List<CurvePoint> curve = e.extras().curve();
    if (curve.isEmpty()) {
      return curve;
    }
    CurvePoint anchor = curve.get(0);
    CurvePoint end = curve.get(curve.size() - 1);
    List<CurvePoint> out = new ArrayList<>();
    out.add(anchor);
    for (Sample s : sampler.samples(e.accountId(), anchor.t(), asOf)) {
      if (s.sampledAt().isBefore(end.t())) {
        out.add(new CurvePoint(s.sampledAt(), null, s.unitValue(), s.unitSeries()));
      }
    }
    if (curve.size() > 1) {
      out.add(end);
    }
    return out;
  }

  /**
   * §16.9 normalization: {@code R(t) = U(t) / U(a) − 1} by the curve's first point (0 there), −1 once the unit series
   * changed (a rebase); points whose ratio is undefined (a non-positive anchor U) are left out.
   */
  static List<RacePointDto> normalize(List<CurvePoint> curve) {
    List<RacePointDto> out = new ArrayList<>();
    if (curve.isEmpty()) {
      return out;
    }
    CurvePoint base = curve.get(0);
    for (CurvePoint p : curve) {
      BigDecimal r = MetricsCalculator.twr(new MetricsCalculator.UnitPoint(base.unitValue(), base.unitSeries()),
          new MetricsCalculator.UnitPoint(p.unitValue(), p.unitSeries()));
      if (r != null) {
        out.add(new RacePointDto(p.t(), r));
      }
    }
    return out;
  }

  /** SPY over the board's window: the period start, each EOD in it and the live end, as {@code B(t) / B(a) − 1}. */
  BenchmarkCurveDto benchmarkCurve(PeriodBoard pb, Board board) {
    BigDecimal start = pb.benchmarkStart();
    List<RacePointDto> points = new ArrayList<>();
    if (start == null || start.signum() <= 0) {
      return new BenchmarkCurveDto(benchmark.symbol(), null, points);
    }
    Instant a = pb.periodStart();
    points.add(new RacePointDto(a, MetricsCalculator.ratioMinusOne(start, start)));
    LocalDate fromExclusive = pb.window().sinceOpening() ? Et.date(a).minusDays(1) : pb.window().anchorDate();
    for (BenchmarkDay d : benchmark.between(benchmark.symbol(), fromExclusive, board.pnlDay())) {
      Instant t = calendar.cutoff(d.tradeDate());
      if (t.isAfter(a) && t.isBefore(board.asOf())) {
        points.add(new RacePointDto(t, MetricsCalculator.ratioMinusOne(d.indexValue(), start)));
      }
    }
    BigDecimal end = pb.benchmarkEnd();
    if (end != null && board.asOf().isAfter(a)) {
      points.add(new RacePointDto(board.asOf(), MetricsCalculator.ratioMinusOne(end, start)));
    }
    return new BenchmarkCurveDto(benchmark.symbol(), pb.benchmarkReturn(), points);
  }
}
