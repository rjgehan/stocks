package com.stocks.portfolio;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.portfolio.config.PerformanceProperties;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The WS4 object graph on a fresh {@link TestDb} (H2, or embedded PostgreSQL under {@code -Ppg-it}) without Spring:
 * a {@link MutableTimeSource}, a {@link ScriptedMarketData}, the Day-0 stub calendar (so {@link PnlCalendar} uses its
 * Mon–Fri fallback: 04:00 pre-open, 20:00 ET cut-off) and §23.4 default settings unless a test passes its own.
 */
public final class PortfolioKit implements AutoCloseable {

  public final TestDb db;
  public final MutableTimeSource time;
  public final ScriptedMarketData market;
  public final MarketCalendar calendar;
  public final SettingsService settingsService;
  public final PerformanceProperties properties = PerformanceProperties.defaults();
  public final TransactionTemplate tx;
  public final List<Object> events = new ArrayList<>();
  public final ApplicationEventPublisher publisher = events::add;

  public final PnlCalendar pnlCalendar;
  public final LeagueSettings settings;
  public final PortfolioRepository repository;
  public final MarkResolver marks;
  public final ValuationServiceImpl valuation;
  public final HoldingsReconstructor holdings;
  public final EodSnapshotRepository eodRepository;
  public final EodValuator valuator;
  public final EodSnapshots snapshots;
  public final RunAnchors anchors;
  public final PeriodResolver periods;
  public final BenchmarkService benchmark;
  public final IntradaySampler sampler;
  public final AccountPerformance accounts;
  public final PortfolioHistoryService history;
  public final PlayerDirectory players;
  public final PortfolioData data;

  public PortfolioKit() {
    this(TestDb.fresh(), TestTimes.clock(), NotImplementedStub.of(MarketCalendar.class, "WS2"),
        NotImplementedStub.of(SettingsService.class, "WS1"));
  }

  public PortfolioKit(TestDb db, MutableTimeSource time, MarketCalendar calendar, SettingsService settingsService) {
    this.db = db;
    this.time = time;
    this.market = new ScriptedMarketData(time);
    this.calendar = calendar;
    this.settingsService = settingsService;
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
    this.pnlCalendar = new PnlCalendar(calendar);
    this.settings = new LeagueSettings(settingsService);
    this.repository = new PortfolioRepository(db.jdbc());
    this.marks = new MarkResolver(market, repository, time);
    this.valuation = new ValuationServiceImpl(repository, marks, time);
    this.holdings = new HoldingsReconstructor(db.jdbc());
    this.eodRepository = new EodSnapshotRepository(db.jdbc(), tx);
    this.valuator = new EodValuator(repository, eodRepository, holdings, marks, pnlCalendar, db.jdbc(), time);
    this.snapshots = new EodSnapshots(eodRepository, valuator, pnlCalendar, time);
    this.anchors = new RunAnchors(repository, snapshots, pnlCalendar);
    this.periods = new PeriodResolver(pnlCalendar, settings);
    this.benchmark = new BenchmarkService(db.jdbc(), marks, pnlCalendar, time, properties);
    this.sampler = new IntradaySampler(valuation, pnlCalendar, db.jdbc(), time, properties);
    this.accounts = new AccountPerformance(repository, valuation, anchors, snapshots, pnlCalendar, db.jdbc(), time);
    this.history = new PortfolioHistoryService(repository, eodRepository, anchors, periods, pnlCalendar, valuation,
        sampler, benchmark, time);
    this.players = new PlayerDirectory(db.jdbc());
    this.data = new PortfolioData(db.jdbc(), time, valuation);
  }

  /** The EOD job with the Day-0 DailyMarkService stub (its NOT_IMPLEMENTED is tolerated). */
  public EodSnapshotJob eodJob() {
    return eodJob(NotImplementedStub.of(DailyMarkService.class, "WS2"));
  }

  public EodSnapshotJob eodJob(DailyMarkService dailyMarks) {
    return new EodSnapshotJob(repository, valuator, eodRepository, snapshots, holdings, benchmark, dailyMarks,
        pnlCalendar, publisher, time, properties);
  }

  public PublicProfileService profiles(LeaderboardService leaderboard) {
    return new PublicProfileService(players, repository, eodRepository, valuation, leaderboard, settings, db.jdbc(),
        time);
  }

  /**
   * Closes the database pool. Every test that builds a kit closes it ({@code @AfterEach}): under {@code -Ppg-it}
   * each kit is a new embedded-PostgreSQL database, and open pools would exhaust {@code max_connections}.
   */
  @Override
  public void close() {
    closePool(db);
  }

  /** Closes a {@link TestDb}'s pool (a Hikari pool, {@code AutoCloseable}). */
  public static void closePool(TestDb db) {
    if (db != null && db.dataSource() instanceof AutoCloseable pool) {
      try {
        pool.close();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Moves the clock to {@code instant} (may go backwards; tests build history in order). */
  public void at(Instant instant) {
    time.set(instant);
  }

  /** A settings service with §23.4 defaults (no feed delay, public mode §16.7) except {@code social.tradeFeedDelay}. */
  public static SettingsService feedDelay(java.time.Duration delay) {
    SettingsService stub = NotImplementedStub.of(SettingsService.class, "WS1");
    return (SettingsService) java.lang.reflect.Proxy.newProxyInstance(SettingsService.class.getClassLoader(),
        new Class<?>[] {SettingsService.class}, (proxy, method, args) -> {
          if (method.getName().equals("tradeFeedDelay")) {
            return delay;
          }
          try {
            return method.invoke(stub, args);
          } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
          }
        });
  }
}
