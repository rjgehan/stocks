package com.stocks.config.stubs;

import com.stocks.common.time.TimeSource;
import com.stocks.config.CommonAutoConfiguration;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.AdminOrderService;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.EngineControl;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Day-0 stub beans so the app boots before the features land (§27.1). Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}: auto-configurations are
 * processed after every user bean, so each {@code @ConditionalOnMissingBean} reliably backs off when a workstream's
 * real bean exists (in component-scanned configuration that condition would be order-dependent).
 *
 * <p>Every stub throws {@code ApiException(NOT_IMPLEMENTED)} (501) from each method, except the market-data default
 * {@link NoopMarketDataService} (every quote {@code quality=NONE}). All of them implement {@link ContractStub}.
 * <b>Each workstream deletes its stubs here in the PR that lands the real bean</b>; at M4 this class is empty and
 * removed. SPIs that are discovered as lists ({@code ScheduledJob}, {@code PeriodicTask},
 * {@code MarketUpdateListener}) need no stub.
 */
@AutoConfiguration(after = CommonAutoConfiguration.class)
public class StubsAutoConfiguration {

  // ===== WS1 =====

  @Bean
  @ConditionalOnMissingBean
  RateLimitService rateLimitServiceStub() {
    return NotImplementedStub.of(RateLimitService.class, "WS1");
  }

  @Bean
  @ConditionalOnMissingBean
  SettingsService settingsServiceStub() {
    return NotImplementedStub.of(SettingsService.class, "WS1");
  }

  @Bean
  @ConditionalOnMissingBean
  PlayerQueries playerQueriesStub() {
    return NotImplementedStub.of(PlayerQueries.class, "WS1");
  }

  // ===== WS2 =====

  @Bean
  @ConditionalOnMissingBean
  MarketCalendar marketCalendarStub() {
    return NotImplementedStub.of(MarketCalendar.class, "WS2");
  }

  @Bean
  @ConditionalOnMissingBean
  CalendarAdmin calendarAdminStub() {
    return NotImplementedStub.of(CalendarAdmin.class, "WS2");
  }

  @Bean
  @ConditionalOnMissingBean
  MarketDataService noopMarketDataService(TimeSource timeSource) {
    return new NoopMarketDataService(timeSource);
  }

  @Bean
  @ConditionalOnMissingBean
  DailyMarkService dailyMarkServiceStub() {
    return NotImplementedStub.of(DailyMarkService.class, "WS2");
  }

  @Bean
  @ConditionalOnMissingBean
  SplitAdjustments splitAdjustmentsStub() {
    return NotImplementedStub.of(SplitAdjustments.class, "WS2");
  }

  // ===== WS3 =====

  @Bean
  @ConditionalOnMissingBean
  OrderService orderServiceStub() {
    return NotImplementedStub.of(OrderService.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  AdminOrderService adminOrderServiceStub() {
    return NotImplementedStub.of(AdminOrderService.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  OrderQueries orderQueriesStub() {
    return NotImplementedStub.of(OrderQueries.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  AccountService accountServiceStub() {
    return NotImplementedStub.of(AccountService.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  AccountQueries accountQueriesStub() {
    return NotImplementedStub.of(AccountQueries.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  CashTransferService cashTransferServiceStub() {
    return NotImplementedStub.of(CashTransferService.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  EngineControl engineControlStub() {
    return NotImplementedStub.of(EngineControl.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  CorporateActionQueries corporateActionQueriesStub() {
    return NotImplementedStub.of(CorporateActionQueries.class, "WS3");
  }

  @Bean
  @ConditionalOnMissingBean
  CorporateActionService corporateActionServiceStub() {
    return NotImplementedStub.of(CorporateActionService.class, "WS3");
  }
}
