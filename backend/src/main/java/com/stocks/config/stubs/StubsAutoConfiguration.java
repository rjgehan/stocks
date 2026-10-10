package com.stocks.config.stubs;

import com.stocks.config.CommonAutoConfiguration;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.watchlist.WatchlistService;
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

  // ===== WS1: none left (RateLimitServiceImpl, SettingsServiceImpl, PlayerQueriesImpl, MeService) =====

  // ===== WS2 =====
  // MarketCalendar, CalendarAdmin, MarketDataService, DailyMarkService and SplitAdjustments are real
  // (marketdata.config.MarketDataAutoConfiguration). NoopMarketDataService stays as a reusable "nothing known" double.

  // ===== WS3 =====
  // OrderService, AdminOrderService, OrderQueries, AccountService, AccountQueries, CashTransferService and
  // EngineControl landed (com.stocks.trading); corporate actions are still stubbed.

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

  // ===== WS4 =====

  @Bean
  @ConditionalOnMissingBean
  ValuationService valuationServiceStub() {
    return NotImplementedStub.of(ValuationService.class, "WS4");
  }

  @Bean
  @ConditionalOnMissingBean
  LeaderboardService leaderboardServiceStub() {
    return NotImplementedStub.of(LeaderboardService.class, "WS4");
  }

  @Bean
  @ConditionalOnMissingBean
  PerformanceQueries performanceQueriesStub() {
    return NotImplementedStub.of(PerformanceQueries.class, "WS4");
  }

  @Bean
  @ConditionalOnMissingBean
  WatchlistService watchlistServiceStub() {
    return NotImplementedStub.of(WatchlistService.class, "WS4");
  }
}
