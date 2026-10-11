package com.stocks.contract;

import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.agents.JournalService;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.marketdata.CorporateActionSource;
import com.stocks.contract.marketdata.DailyMarkService;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.LeagueStatsQueries;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.portfolio.ValuationService;
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
import com.stocks.contract.watchlist.WatchlistService;
import java.util.List;

/**
 * Every §5.3 contract service interface the application needs exactly one bean of: a workstream's real bean, or the
 * Day-0 stub from {@code StubsAutoConfiguration} until it lands (§27.1). Workstreams delete stubs, never entries here;
 * a new contract service interface must be added here, which {@code StubsAutoConfigurationTest} checks (since M3 a
 * contract lands with its real bean, so it needs no stub: {@code CorporateActionSource}).
 */
final class ContractServices {

  static final List<Class<?>> ALL = List.of(
      // WS1
      RateLimitService.class, SettingsService.class, PlayerQueries.class, MeQueries.class,
      // WS2
      MarketCalendar.class, CalendarAdmin.class, MarketDataService.class, DailyMarkService.class,
      SplitAdjustments.class, CorporateActionSource.class,
      // WS3
      OrderService.class, AdminOrderService.class, OrderQueries.class, AccountService.class, AccountQueries.class,
      CashTransferService.class, EngineControl.class, CorporateActionQueries.class, CorporateActionService.class,
      // WS4
      ValuationService.class, LeaderboardService.class, PerformanceQueries.class, WatchlistService.class,
      // WS4, Phase 2 league slice (§16.8–16.11)
      LeagueStatsQueries.class, PnlAnalysisQueries.class, FeedReactionService.class,
      // WS5, Phase 2 (§17.10, §20.7)
      ApiAccessRecorder.class, JournalService.class);

  private ContractServices() {}
}
