package com.stocks.league;

import com.stocks.common.id.PublicId;
import com.stocks.config.JacksonConfig;
import com.stocks.portfolio.PortfolioKit;
import com.stocks.portfolio.TradeHistoryRepository;
import java.util.concurrent.AbstractExecutorService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import tools.jackson.databind.json.JsonMapper;

/**
 * The league object graph on a {@link PortfolioKit} (fresh H2, or embedded PostgreSQL under {@code -Ppg-it}). Feed
 * events are recorded synchronously; STOMP sends go to {@link #messaging} when a test registers one.
 */
public final class LeagueKit {

  public final PortfolioKit kit;
  public final JsonMapper json = JacksonConfig.newMapper();
  public final StaticListableBeanFactory beans = new StaticListableBeanFactory();
  public final ObjectProvider<SimpMessageSendingOperations> messaging =
      beans.getBeanProvider(SimpMessageSendingOperations.class);
  public final LeagueRepository league;
  public final LeaderboardComputer computer;
  public final LeaderboardServiceImpl leaderboard;
  public final FeedRepository feedRepository;
  public final FeedReactionRepository reactionRepository;
  public final FeedEventListener feed;
  public final FeedService feedService;
  public final LeaderboardSnapshotWriter snapshotWriter;
  // Phase 2 (§16.8–16.11)
  public final TradeHistoryRepository trades;
  public final PlayerStatsService stats;
  public final FeedReactionServiceImpl reactions;

  public LeagueKit() {
    this(new PortfolioKit());
  }

  public LeagueKit(PortfolioKit kit) {
    this.kit = kit;
    this.league = new LeagueRepository(kit.db.jdbc());
    this.computer = new LeaderboardComputer(kit.players, kit.repository, league, kit.snapshots, kit.eodRepository,
        kit.periods, kit.pnlCalendar, kit.benchmark, kit.settings, kit.valuation, kit.time);
    this.leaderboard = new LeaderboardServiceImpl(computer, kit.settings, kit.benchmark, messaging, kit.properties);
    this.feedRepository = new FeedRepository(kit.db.jdbc());
    this.reactionRepository = new FeedReactionRepository(kit.db.jdbc());
    this.feed = new FeedEventListener(feedRepository, kit.db.jdbc(), new PublicId(kit.time), json, kit.settings,
        new DirectExecutor());
    this.feedService = new FeedService(feedRepository, reactionRepository, kit.players, json, kit.time, messaging);
    this.snapshotWriter = new LeaderboardSnapshotWriter(computer, league, feed, kit.pnlCalendar, kit.time);
    this.trades = new TradeHistoryRepository(kit.db.jdbc());
    this.stats = new PlayerStatsService(leaderboard, league, trades, kit.repository, kit.profiles(leaderboard),
        kit.players, kit.settings, kit.sampler, kit.benchmark, kit.pnlCalendar);
    this.reactions = new FeedReactionServiceImpl(reactionRepository, feedService, kit.players, kit.time);
  }

  /** Runs every task on the calling thread (deterministic feed tests). */
  static final class DirectExecutor extends AbstractExecutorService {
    private volatile boolean shutdown;

    @Override
    public void execute(Runnable command) {
      command.run();
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }
}
