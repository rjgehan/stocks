package com.stocks.league;

import com.stocks.common.domain.KindFilter;
import com.stocks.common.domain.LeaderboardMetric;
import com.stocks.common.domain.LeaderboardPeriod;
import com.stocks.common.money.Money;
import com.stocks.contract.jobs.PeriodicTask;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.LeaderboardDto.BenchmarkPlayerDto;
import com.stocks.contract.portfolio.dto.LeaderboardDto.BenchmarkReturnDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.realtime.dto.LeaderboardUpdateDto;
import com.stocks.league.LeaderboardComputer.Board;
import com.stocks.league.LeaderboardComputer.PeriodBoard;
import com.stocks.portfolio.BenchmarkService;
import com.stocks.portfolio.LeagueSettings;
import com.stocks.portfolio.config.PerformanceProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * The live, deposit-aware leaderboard (§16.5). A {@link PeriodicTask} ({@code LEADERBOARD_RECOMPUTE}, every
 * {@code stocks.performance.leaderboard-refresh} = 5 s) recomputes every period with {@link LeaderboardComputer} and
 * caches it; {@code GET /leaderboard} and MCP {@code get_leaderboard} read the cache (recomputing on demand when it
 * is older than the interval, e.g. with jobs disabled). When anything changed, {@code {asOf}} goes to
 * {@code /topic/leaderboard} (if a STOMP broker is configured), and clients refetch.
 */
@Service
public class LeaderboardServiceImpl implements LeaderboardService, PeriodicTask {

  private static final Logger log = LoggerFactory.getLogger(LeaderboardServiceImpl.class);

  public static final String NAME = "LEADERBOARD_RECOMPUTE";
  public static final String TOPIC = "/topic/leaderboard";
  static final int DEFAULT_LIMIT = 50;
  static final int MAX_LIMIT = 100;

  private final LeaderboardComputer computer;
  private final LeagueSettings settings;
  private final BenchmarkService benchmark;
  private final ObjectProvider<SimpMessageSendingOperations> messaging;
  private final Duration refresh;

  private volatile Board cached;
  private volatile long cachedAtNanos;
  private volatile int fingerprint;

  public LeaderboardServiceImpl(LeaderboardComputer computer, LeagueSettings settings, BenchmarkService benchmark,
                                ObjectProvider<SimpMessageSendingOperations> messaging,
                                PerformanceProperties properties) {
    this.computer = computer;
    this.settings = settings;
    this.benchmark = benchmark;
    this.messaging = messaging;
    this.refresh = properties.leaderboardRefresh();
  }

  // ------------------------------------------------------------------------------------------- PeriodicTask

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Duration interval() {
    return refresh;
  }

  @Override
  public boolean sessionsOnly() {
    return false;                                    // deposits and resets happen on weekends too
  }

  @Override
  public void tick() {
    refresh();
  }

  /** Recomputes now; publishes {@code {asOf}} when any number changed. */
  public synchronized Board refresh() {
    Board board = computer.live();
    cached = board;
    cachedAtNanos = System.nanoTime();
    int fp = fingerprint(board);
    if (fp != fingerprint) {
      fingerprint = fp;
      publish(board);
    }
    return board;
  }

  private void publish(Board board) {
    SimpMessageSendingOperations ops = messaging.getIfAvailable();
    if (ops == null) {
      return;
    }
    try {
      ops.convertAndSend(TOPIC, new LeaderboardUpdateDto(board.asOf()));
    } catch (RuntimeException e) {
      log.debug("Could not publish {}: {}", TOPIC, e.toString());
    }
  }

  static int fingerprint(Board board) {
    int h = 1;
    for (PeriodBoard p : board.periods().values()) {
      for (LeagueEntry e : p.entries()) {
        h = 31 * h + Objects.hash(e.player().id(), e.twr(), e.pnl(), e.eligible(), e.equity(), e.fills());
      }
    }
    return h;
  }

  /**
   * The cached board (recomputed when older than the refresh interval): the one computation the leaderboard, player
   * stats and the race chart all read (§16.8, §16.9).
   */
  public Board current() {
    Board b = cached;
    if (b == null || System.nanoTime() - cachedAtNanos > refresh.toNanos()) {
      return refresh();
    }
    return b;
  }

  // ------------------------------------------------------------------------------------------- LeaderboardService

  @Override
  public LeaderboardDto board(LeaderboardPeriod period, LeaderboardMetric metric, KindFilter kind, int limit,
                              Long viewerPlayerId) {
    LeaderboardPeriod p = period == null ? LeaderboardPeriod.WEEK : period;
    LeaderboardMetric m = metric == null ? LeaderboardMetric.RETURN : metric;
    KindFilter k = kind == null ? KindFilter.ALL : kind;
    int n = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    Board board = current();
    PeriodBoard pb = board.periods().get(p);
    PeriodBoard today = board.periods().get(LeaderboardPeriod.TODAY);
    PeriodBoard since = board.periods().get(LeaderboardPeriod.ALL);
    List<LeaderboardRowDto> all = LeaderboardRowMapper.rows(pb.entries(), m, k, settings.humanKindExcludesApiTraders(),
        LeaderboardRowMapper.byPlayer(today.entries()), LeaderboardRowMapper.byPlayer(since.entries()));
    LeaderboardRowDto me = null;
    if (viewerPlayerId != null) {
      String viewer = pb.entries().stream().filter(e -> e.player().id() == viewerPlayerId)
          .map(e -> e.player().publicId()).findFirst().orElse(null);
      if (viewer != null) {
        me = all.stream().filter(r -> r.player().playerId().equals(viewer)).findFirst().orElse(null);
      }
    }
    return new LeaderboardDto(p, m, k, pb.periodStart(), board.asOf(),
        new BenchmarkReturnDto(benchmark.symbol(), pb.benchmarkReturn()), LeaderboardRowMapper.arena(pb.entries()),
        all.subList(0, Math.min(n, all.size())), me,
        benchmarkPlayer(benchmark.symbol(), settings.defaultStartingCash(), pb, today, since));
  }

  /**
   * SPY as a player: {@code cash} put into SPY at the league's first opening (B0 = the ALL period's start) and held,
   * so its equity is {@code cash * B_end / B0} and its P&amp;L over a period starting at B_a is
   * {@code cash * (B_end - B_a) / B0}. Null where a benchmark value is unknown.
   */
  static BenchmarkPlayerDto benchmarkPlayer(String symbol, BigDecimal cash, PeriodBoard period, PeriodBoard today,
                                            PeriodBoard since) {
    BigDecimal origin = since.benchmarkStart();
    BigDecimal end = period.benchmarkEnd();
    return new BenchmarkPlayerDto(symbol, cash, scaled(cash, end, origin), period.benchmarkReturn(),
        benchmarkPnl(cash, origin, period.benchmarkStart(), end), today.benchmarkReturn(),
        benchmarkPnl(cash, origin, today.benchmarkStart(), end), since.benchmarkReturn(),
        benchmarkPnl(cash, origin, origin, end));
  }

  private static BigDecimal benchmarkPnl(BigDecimal cash, BigDecimal origin, BigDecimal start, BigDecimal end) {
    return start == null || end == null ? null : scaled(cash, end.subtract(start), origin);
  }

  /** {@code cash * x / origin} in cents; null when anything is unknown or the origin is not positive. */
  private static BigDecimal scaled(BigDecimal cash, BigDecimal x, BigDecimal origin) {
    if (cash == null || x == null || origin == null || origin.signum() <= 0) {
      return null;
    }
    return cash.multiply(x).divide(origin, Money.SCALE, RoundingMode.HALF_EVEN);
  }
}
