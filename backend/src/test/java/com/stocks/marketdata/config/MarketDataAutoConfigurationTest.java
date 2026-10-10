package com.stocks.marketdata.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.calendar.AlwaysOpenCalendar;
import com.stocks.calendar.DbMarketCalendar;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.time.OffsetTimeSource;
import com.stocks.common.time.SystemTimeSource;
import com.stocks.common.time.TimeSource;
import com.stocks.config.CommonAutoConfiguration;
import com.stocks.config.stubs.StubsAutoConfiguration;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.marketdata.core.MarketDataServiceImpl;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * WS2's auto-configuration: the real contract beans replace the Day-0 stubs, a test's own {@code MarketDataService}
 * (the test kit's {@code ScriptedMarketData}) or {@code MarketCalendar} wins, and the SIM clock modes pick the
 * application {@link TimeSource} (§15.7, §23.2).
 */
class MarketDataAutoConfigurationTest {

  final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class, MarketDataClockAutoConfiguration.class,
          MarketDataAutoConfiguration.class, StubsAutoConfiguration.class))
      .withBean(DataSource.class, () -> TestDb.h2().dataSource())       // a context closes its pool
      .withPropertyValues("stocks.marketdata.sim.clock=fixed", "stocks.marketdata.sim.anchor=2026-10-09T14:00:00Z");

  @Test
  void realBeansReplaceTheStubs() {
    runner.run(ctx -> {
      assertThat(ctx).hasNotFailed();
      assertThat(ctx.getBean(MarketDataService.class)).isInstanceOf(MarketDataServiceImpl.class);
      assertThat(ctx.getBean(MarketCalendar.class)).isInstanceOf(DbMarketCalendar.class);
      assertThat(ctx).hasSingleBean(CalendarAdmin.class).hasSingleBean(DailyMarkService.class)
          .hasSingleBean(SplitAdjustments.class);
      assertThat(ctx.getBean(TimeSource.class)).isInstanceOf(MutableTimeSource.class);
      assertThat(ctx.getBean(TimeSource.class).now()).isEqualTo(TestTimes.START);
      assertThat(ctx.getBean(MarketDataService.class).quote("AAPL").bid()).isNotNull();
    });
  }

  @Test
  void aTestsOwnMarketDataAndCalendarWin() {
    MutableTimeSource clock = TestTimes.clock();
    runner.withBean(MutableTimeSource.class, () -> clock)
        .withBean(ScriptedMarketData.class, () -> new ScriptedMarketData(clock))
        .withBean(MarketCalendar.class, AlwaysOpenCalendar::new)
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          assertThat(ctx.getBean(MarketDataService.class)).isInstanceOf(ScriptedMarketData.class);
          assertThat(ctx.getBean(MarketCalendar.class)).isInstanceOf(AlwaysOpenCalendar.class);
          assertThat(ctx.getBean(TimeSource.class)).isSameAs(clock);
        });
  }

  @Test
  void alwaysOpenAndClockModes() {
    runner.withPropertyValues("stocks.marketdata.sim.always-open=true", "stocks.marketdata.sim.clock=offset")
        .run(ctx -> {
          assertThat(ctx.getBean(MarketCalendar.class)).isInstanceOf(AlwaysOpenCalendar.class);
          assertThat(ctx.getBean(TimeSource.class)).isInstanceOf(OffsetTimeSource.class);
          assertThat(ctx.getBean(TimeSource.class).now()).isBetween(TestTimes.START,
              TestTimes.START.plusSeconds(60));
        });
    runner.withPropertyValues("stocks.marketdata.sim.clock=wall").run(ctx ->
        assertThat(ctx.getBean(TimeSource.class)).isInstanceOf(SystemTimeSource.class));
    runner.withPropertyValues("stocks.marketdata.sim.always-open=true")
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
