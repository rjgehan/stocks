package com.stocks.marketdata.config;

import com.stocks.calendar.AlwaysOpenCalendar;
import com.stocks.calendar.CalendarAdminImpl;
import com.stocks.calendar.CalendarRepository;
import com.stocks.calendar.CalendarSyncJob;
import com.stocks.calendar.DbMarketCalendar;
import com.stocks.common.time.TimeSource;
import com.stocks.config.CommonAutoConfiguration;
import com.stocks.config.stubs.StubsAutoConfiguration;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.CorporateActionSource;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketUpdateListener;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.jobs.JobScheduler;
import com.stocks.marketdata.alpaca.AlpacaCapabilityProbe;
import com.stocks.marketdata.core.AssetSyncJob;
import com.stocks.marketdata.core.DailyMarkJob;
import com.stocks.marketdata.core.HoldingLeases;
import com.stocks.marketdata.core.MarketDataServiceImpl;
import com.stocks.marketdata.core.MoversService;
import com.stocks.marketdata.core.PrevCloseJob;
import com.stocks.marketdata.core.RemarkJob;
import com.stocks.marketdata.core.SplitAdjustmentsImpl;
import com.stocks.marketdata.core.SplitGuardJob;
import com.stocks.marketdata.core.Universe;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * WS2's beans (§15, §9): the market calendar, calendar overrides, the market-data runtime of the configured profile
 * ({@link MarketDataRuntime}), the {@link MarketDataService}, {@link DailyMarkService} and {@link SplitAdjustments}
 * contracts, and the market-data jobs (§25). An auto-configuration (listed in {@code AutoConfiguration.imports}),
 * processed after every user bean, so a test's own {@code MarketCalendar}, {@code MarketDataService} (the test kit's
 * {@code ScriptedMarketData}) or {@code SplitAdjustments} reliably replaces the real one through
 * {@code @ConditionalOnMissingBean} (the same reasoning as {@code StubsAutoConfiguration}, §27.1).
 */
@AutoConfiguration(after = {MarketDataClockAutoConfiguration.class, CommonAutoConfiguration.class},
    before = StubsAutoConfiguration.class)
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MarketDataAutoConfiguration.class);

  // ------------------------------------------------------------------------------------------- calendar (§9)

  @Bean
  Universe marketUniverse() {
    return Universe.load();
  }

  @Bean
  @DependsOnDatabaseInitialization
  CalendarRepository calendarRepository(DataSource dataSource) {
    return new CalendarRepository(dataSource);
  }

  @Bean
  @ConditionalOnMissingBean(MarketCalendar.class)
  @DependsOnDatabaseInitialization
  MarketCalendar marketCalendar(MarketDataProperties props, CalendarRepository repository, TimeSource time,
                                Environment env) {
    if (props.sim().alwaysOpen()) {
      if (env.acceptsProfiles(Profiles.of("prod"))) {
        throw new IllegalStateException("stocks.marketdata.sim.always-open=true is not allowed in production (§9.3)");
      }
      if (props.simulated()) {
        log.info("Market calendar: always open (dev/e2e SIM, §9.3)");
        return new AlwaysOpenCalendar();
      }
      log.warn("stocks.marketdata.sim.always-open ignored: profile {} uses the real calendar", props.profile());
    }
    DbMarketCalendar calendar = new DbMarketCalendar(repository, time);
    calendar.initialize();
    return calendar;
  }

  @Bean
  @ConditionalOnMissingBean(CalendarAdmin.class)
  CalendarAdmin calendarAdmin(CalendarRepository repository, MarketCalendar calendar, TimeSource time,
                              ObjectProvider<JobScheduler> scheduler) {
    return new CalendarAdminImpl(repository, calendar instanceof DbMarketCalendar db ? db : null, time,
        () -> scheduler.ifAvailable(JobScheduler::replan));
  }

  @Bean
  CalendarSyncJob calendarSyncJob(CalendarRepository repository, MarketCalendar calendar, MarketDataRuntime runtime,
                                  TimeSource time, ObjectProvider<JobScheduler> scheduler) {
    var alpaca = runtime.alpacaOrNull();
    return new CalendarSyncJob(repository, calendar instanceof DbMarketCalendar db ? db : null,
        alpaca == null ? null : alpaca::calendar, time, () -> scheduler.ifAvailable(JobScheduler::replan));
  }

  // ------------------------------------------------------------------------------------------- market data (§15)

  @Bean
  @ConditionalOnMissingBean(SplitAdjustments.class)
  @DependsOnDatabaseInitialization
  SplitAdjustmentsImpl splitAdjustments(DataSource dataSource, TimeSource time) {
    return new SplitAdjustmentsImpl(dataSource, time);
  }

  @Bean(destroyMethod = "close")
  @DependsOnDatabaseInitialization
  MarketDataRuntime marketDataRuntime(MarketDataProperties props, Environment env, DataSource dataSource,
                                      TimeSource time, MarketCalendar calendar, Universe universe,
                                      SplitAdjustments splits, ObjectProvider<CorporateActionQueries> corporateActions,
                                      ObjectProvider<CorporateActionService> corporateActionService) {
    MarketDataRuntime.External external = new MarketDataRuntime.External(
        () -> {
          CorporateActionQueries q = corporateActions.getIfAvailable();
          return q == null ? List.of() : q.symbolsOnHold();
        },
        d -> {
          CorporateActionService s = corporateActionService.getIfAvailable();
          if (s != null) {
            s.flagDetectedSplit(d.symbol(), d.exDate(), d.oldRate(), d.newRate());
          }
        });
    return new MarketDataRuntime(props, ExecQuoteRules.from(env), dataSource, time, calendar, universe, splits,
        external);
  }

  @Bean
  @ConditionalOnMissingBean(MarketDataService.class)
  MarketDataServiceImpl marketDataService(MarketDataRuntime runtime) {
    return runtime.service();
  }

  @Bean
  @ConditionalOnMissingBean(DailyMarkService.class)
  DailyMarkService dailyMarkService(MarketDataRuntime runtime) {
    return runtime.dailyMarkService();
  }

  /** §14 corporate-action feed for WS3's sync job: the profile's primary provider (Alpaca or the SIM scenarios). */
  @Bean
  @ConditionalOnMissingBean(CorporateActionSource.class)
  CorporateActionSource corporateActionSource(MarketDataRuntime runtime) {
    return (symbols, from, to) -> runtime.primary().corporateActions(symbols, from, to);
  }

  /**
   * Starts the feed once every singleton exists (phase −100: before {@code StartupGate} opens the engine and before
   * the web server), attaching every {@link MarketUpdateListener} bean; live profiles then probe Alpaca and sync
   * assets on a background thread, so an unreachable provider never delays or fails the boot (§15.9).
   */
  @Bean
  SmartLifecycle marketDataLifecycle(MarketDataRuntime runtime, ObjectProvider<MarketUpdateListener> listeners,
                                     CalendarSyncJob calendarSync) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        runtime.start(listeners.orderedStream().toList());
        running = true;
        if (!runtime.props().simulated()) {
          Thread.ofVirtual().name("marketdata-boot").start(() -> {
            try {
              calendarSync.sync();
            } catch (RuntimeException e) {
              log.warn("Boot calendar sync failed: {}", e.toString());
            }
            runtime.bootSync();
          });
        }
      }

      @Override
      public void stop() {
        runtime.close();
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }

      @Override
      public int getPhase() {
        return -100;
      }
    };
  }

  // ------------------------------------------------------------------------------------------- jobs (§25)

  @Bean
  AssetSyncJob assetSyncJob(MarketDataRuntime runtime) {
    return new AssetSyncJob(runtime.assetSync());
  }

  @Bean
  DailyMarkJob dailyMarkJob(DailyMarkService dailyMarks, MarketDataRuntime runtime) {
    return new DailyMarkJob(dailyMarks, runtime::officialCloseSymbols, runtime::dayEndSymbols);
  }

  @Bean
  RemarkJob remarkJob(DailyMarkService dailyMarks, MarketDataRuntime runtime) {
    return new RemarkJob(dailyMarks, runtime.dailyMarks());
  }

  @Bean
  PrevCloseJob prevCloseJob(DailyMarkService dailyMarks, MarketDataRuntime runtime, MarketCalendar calendar) {
    return new PrevCloseJob(dailyMarks, runtime.dailyMarks(), runtime.feed(), runtime.splitGuard(),
        runtime::officialCloseSymbols, calendar);
  }

  @Bean
  SplitGuardJob splitGuardJob(MarketDataRuntime runtime) {
    return new SplitGuardJob(runtime.splitGuard());
  }

  @Bean
  HoldingLeases holdingLeases(MarketDataRuntime runtime) {
    return new HoldingLeases(runtime.tracked(), runtime.budget());
  }

  @Bean
  MoversService moversService(MarketDataRuntime runtime) {
    return runtime.movers();
  }

  /** Live profiles only: {@code ALPACA_PROBE} at 03:10 ET (it also runs at boot). */
  @Bean
  @Conditional(LiveProfileCondition.class)
  AlpacaCapabilityProbe alpacaCapabilityProbe(MarketDataRuntime runtime) {
    return runtime.probeOrNull();
  }

  /** {@code stocks.marketdata.profile} is not {@code simulated}. */
  static final class LiveProfileCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String p = context.getEnvironment().getProperty("stocks.marketdata.profile", MarketDataProperties.SIMULATED);
      return !MarketDataProperties.SIMULATED.equalsIgnoreCase(p.trim()) && !p.isBlank();
    }
  }

}
