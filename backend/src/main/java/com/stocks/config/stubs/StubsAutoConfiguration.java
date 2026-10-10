package com.stocks.config.stubs;

import com.stocks.config.CommonAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Day-0 stub beans so the app boots before the features land (§27.1). Registered through
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}: auto-configurations are
 * processed after every user bean, so each {@code @ConditionalOnMissingBean} reliably backs off when a workstream's
 * real bean exists (in component-scanned configuration that condition would be order-dependent).
 *
 * <p>Every stub throws {@code ApiException(NOT_IMPLEMENTED)} (501) from each method, except the market-data default
 * {@link NoopMarketDataService} (every quote {@code quality=NONE}). All of them implement {@link ContractStub}.
 * <b>Each workstream deletes its stubs here in the PR that lands the real bean</b>. Since the corporate-action
 * contracts landed (M3) no stub bean is left: the class stays as the documented extension point for a future
 * contract's Day-0 stub, and {@code ContractStubsIT} fails if any {@link ContractStub} bean reaches the
 * application. SPIs that are discovered as lists ({@code ScheduledJob}, {@code PeriodicTask},
 * {@code MarketUpdateListener}) need no stub.
 */
@AutoConfiguration(after = CommonAutoConfiguration.class)
public class StubsAutoConfiguration {

  // ===== WS1: none left (RateLimitServiceImpl, SettingsServiceImpl, PlayerQueriesImpl, MeService) =====

  // ===== WS2 =====
  // MarketCalendar, CalendarAdmin, MarketDataService, DailyMarkService and SplitAdjustments are real
  // (marketdata.config.MarketDataAutoConfiguration). NoopMarketDataService stays as a reusable "nothing known" double.

  // ===== WS3 =====
  // OrderService, AdminOrderService, OrderQueries, AccountService, AccountQueries, CashTransferService, EngineControl,
  // CorporateActionQueries and CorporateActionService are real (com.stocks.trading).

  // ===== WS4 =====
  // ValuationService, LeaderboardService, PerformanceQueries and WatchlistService are real (portfolio, league,
  // watchlist).
}
