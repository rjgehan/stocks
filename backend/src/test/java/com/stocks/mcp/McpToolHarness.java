package com.stocks.mcp;

import static org.mockito.Mockito.mock;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.SessionType;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.calendar.TradingDay;
import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.portfolio.FeedReactionService;
import com.stocks.contract.portfolio.LeaderboardService;
import com.stocks.contract.portfolio.LeagueStatsQueries;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PnlAnalysisQueries;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.mcp.tools.AccountIdentityTools;
import com.stocks.mcp.tools.AccountTools;
import com.stocks.mcp.tools.LeagueStatsTools;
import com.stocks.mcp.tools.LeagueTools;
import com.stocks.mcp.tools.MarketTools;
import com.stocks.mcp.tools.McpToolSet;
import com.stocks.mcp.tools.NotificationTools;
import com.stocks.mcp.tools.OrderTools;
import com.stocks.mcp.tools.WatchlistTools;
import com.stocks.realtime.NotificationService;
import com.stocks.realtime.QuoteMessages;
import com.stocks.realtime.RecordingMarketData;
import com.stocks.realtime.SubscriptionTracker;
import com.stocks.realtime.UserPush;
import com.stocks.rules.RulesService;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every §20.3 tool wired to test doubles without a Spring context: Mockito mocks for the WS1/WS3/WS4 contracts (each
 * test stubs what it needs), {@link RecordingMarketData} for market data, and the real {@code RulesService} and
 * {@code NotificationService} on a fresh database. {@link #call} goes through the same {@code McpToolHandler} as the
 * server (scope check, argument binding, Boot-mapper results, §20.2 errors).
 */
final class McpToolHarness implements AutoCloseable {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();
  static final RequestMeta META = new RequestMeta("req_1", Channel.MCP_KEY, "127.0.0.1", "ua", "claude-code", null);

  final MutableTimeSource time = TestTimes.clock();
  final TestDb db = TestDb.fresh();
  final RecordingMarketData marketData = new RecordingMarketData(time);
  final MarketCalendar calendar = mock(MarketCalendar.class);
  final MeQueries me = mock(MeQueries.class);
  final AccountQueries accounts = mock(AccountQueries.class);
  final OrderQueries orders = mock(OrderQueries.class);
  final OrderService orderService = mock(OrderService.class);
  final PerformanceQueries performance = mock(PerformanceQueries.class);
  final LeaderboardService leaderboard = mock(LeaderboardService.class);
  final LeagueStatsQueries leagueStats = mock(LeagueStatsQueries.class);
  final PnlAnalysisQueries pnlAnalysis = mock(PnlAnalysisQueries.class);
  final FeedReactionService reactions = mock(FeedReactionService.class);
  final PlayerQueries players = mock(PlayerQueries.class);
  final WatchlistService watchlists = mock(WatchlistService.class);
  final RateLimitService rateLimits = mock(RateLimitService.class);
  final SubscriptionTracker tracker = new SubscriptionTracker(marketData, time);
  final Fixtures.PlayerRef owner;
  final Fixtures.PlayerRef botPlayer;
  final NotificationService notifications;
  final RulesService rules;
  final List<McpToolSet> toolSets;
  final Map<String, SyncToolSpecification> specs = new LinkedHashMap<>();

  /**
   * Closes a {@link TestDb}'s connection pool. Every {@code TestDb.fresh()} opens a pool (on its own embedded
   * PostgreSQL database under {@code -Ppg-it}); left open, a few dozen tests exhaust the server's connections.
   */
  static void closeDb(TestDb db) {
    if (db != null && db.dataSource() instanceof com.zaxxer.hikari.HikariDataSource pool) {
      pool.close();
    }
  }

  @Override
  public void close() {
    closeDb(db);
  }

  McpToolHarness() {
    Fixtures fx = new Fixtures(db.jdbc(), time);
    owner = fx.human("owner");
    botPlayer = fx.ai("claude", owner);
    DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
    factory.registerSingleton("messaging", mock(SimpMessageSendingOperations.class));
    UserPush push = new UserPush(tracker, factory.getBeanProvider(SimpMessageSendingOperations.class));
    notifications = new NotificationService(db.jdbc(), new PublicId(time), time, MAPPER, push);
    rules = new RulesService(new MockEnvironment(), TestProperties.stocks(),
        NotImplementedStub.of(SettingsService.class, "WS1"), db.jdbc(), time, MAPPER);
    QuoteMessages quoteMessages = new QuoteMessages(time, calendar);
    Mockito.when(calendar.stateAt(Mockito.any())).thenReturn(new SessionState(SessionType.REGULAR,
        TestTimes.TRADE_DATE, Instant.parse("2026-10-09T13:30:00Z"), Instant.parse("2026-10-09T20:00:00Z"),
        SessionType.POST, Instant.parse("2026-10-09T20:00:00Z")));
    toolSets = List.of(
        new AccountIdentityTools(me, accounts, rateLimits),
        new MarketTools(rules, calendar, marketData, quoteMessages, tracker, time),
        new AccountTools(accounts, orders, performance, time),
        new OrderTools(orderService, orders, accounts),
        new LeagueTools(leaderboard, players, performance),
        new WatchlistTools(watchlists),
        new NotificationTools(notifications),
        new LeagueStatsTools(leagueStats, pnlAnalysis, reactions, accounts));
    for (SyncToolSpecification s : McpToolCatalog.specifications(toolSets, MAPPER)) {
      specs.put(s.tool().name(), s);
    }
  }

  /** A trading day 2026-10-09 (09:30–16:00 ET) in the mock calendar. */
  static TradingDay tradingDay(LocalDate d) {
    return new TradingDay(d, Instant.parse(d + "T08:00:00Z"), Instant.parse(d + "T13:30:00Z"),
        Instant.parse(d + "T20:00:00Z"), Instant.parse(d.plusDays(1) + "T00:00:00Z"), d.plusDays(1), false,
        "BUNDLED");
  }

  Tool tool(String name) {
    return specs.get(name).tool();
  }

  /** The bot player (a real {@code player} row, so notifications can reference it) with an all-scope key. */
  PlayerPrincipal bot() {
    return TestPrincipals.apiKey(botPlayer.id(), botPlayer.publicId(), botPlayer.handle(), PlayerKind.AI,
        Scopes.ALL, 9);
  }

  PlayerPrincipal keyWith(String... scopes) {
    return TestPrincipals.apiKey(botPlayer.id(), botPlayer.publicId(), botPlayer.handle(), PlayerKind.AI,
        Set.of(scopes), 9);
  }

  /** A human on a browser session (all scopes). */
  PlayerPrincipal human() {
    return TestPrincipals.session(owner.id(), owner.publicId(), owner.handle());
  }

  CallToolResult call(String tool, Map<String, Object> args) {
    return call(tool, bot(), args);
  }

  CallToolResult call(String tool, PlayerPrincipal who, Map<String, Object> args) {
    SyncToolSpecification spec = Optional.ofNullable(specs.get(tool))
        .orElseThrow(() -> new IllegalArgumentException("no tool " + tool));
    return spec.callHandler().apply(CurrentPlayer.mcpContext(who, META), new CallToolRequest(tool, args));
  }

  /** The structured result as a tree (fails the test if the call was an error). */
  static JsonNode ok(CallToolResult r) {
    if (Boolean.TRUE.equals(r.isError())) {
      throw new AssertionError("tool error: " + text(r));
    }
    return MAPPER.valueToTree(r.structuredContent());
  }

  /** The §20.2 error body of an error result. */
  static JsonNode error(CallToolResult r) {
    if (!Boolean.TRUE.equals(r.isError())) {
      throw new AssertionError("expected an error, got " + text(r));
    }
    return MAPPER.readTree(text(r));
  }

  static String text(CallToolResult r) {
    return ((TextContent) r.content().get(0)).text();
  }
}
