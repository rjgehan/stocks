package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.LiquidityTier;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.web.Page;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.marketdata.model.Fundamentals;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.LeaderboardDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.contract.security.RateLimitStatus;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.ViolationDto;
import com.stocks.contract.watchlist.dto.WatchlistDto;
import com.stocks.mcp.McpOutputSchemasTest.Samples;
import com.stocks.testkit.TestTimes;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The §20.2 MCP JSON contract for every read tool: {@code structuredContent} validates against the tool's advertised
 * {@code outputSchema} with the MCP SDK's own validator (the one the server runs on every result), decimals are JSON
 * strings (no floating-point number anywhere), null members are present, and the text block is the same JSON. Each
 * tool runs twice: with fully populated DTOs and with every nullable member null.
 */
class McpJsonContractTest {

  static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();
  static final LocalDate HOLIDAY = LocalDate.of(2026, 11, 26);

  McpToolHarness h;

  @org.junit.jupiter.api.AfterEach
  void closeHarness() {
    h.close();
  }

  @BeforeEach
  void setUp() {
    h = new McpToolHarness();
    h.marketData.withAsset("AAPL").withAsset(new Asset("SPY", "SPDR S&P 500 ETF", "ARCA",
            com.stocks.common.domain.AssetType.ETF, true, true, true, true, true, LiquidityTier.MEGA, true))
        .prevClose("AAPL", "225.00").quote("AAPL", TestTimes.START, "227.39", 300, "227.41", 200)
        .print("AAPL", TestTimes.START, "227.40", 100)
        .fundamentals(new Fundamentals("AAPL", new BigDecimal("3420000000000"), new BigDecimal("34.10"),
            new BigDecimal("6.67"), new BigDecimal("0.0044"), new BigDecimal("260.10"), new BigDecimal("164.08"),
            new BigDecimal("1.24"), new BigDecimal("15100000000"), "Technology", "https://apple.com", "finnhub"));
    h.marketData.withBars("AAPL", new Bar("AAPL", Timeframe.MIN1, TestTimes.START, new BigDecimal("227.10"),
        new BigDecimal("227.45"), new BigDecimal("227.05"), new BigDecimal("227.40"), 300, new BigDecimal("227.3"), 3,
        true));
    when(h.calendar.day(any())).thenAnswer(inv -> {
      LocalDate d = inv.getArgument(0);
      boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
      return weekend || d.equals(HOLIDAY) ? Optional.empty() : Optional.of(McpToolHarness.tradingDay(d));
    });
    when(h.calendar.isTradingDay(any())).thenAnswer(inv -> h.calendar.day(inv.getArgument(0)).isPresent());
    when(h.calendar.nextTradingDay(any())).thenAnswer(inv -> {
      LocalDate d = ((LocalDate) inv.getArgument(0)).plusDays(1);
      while (h.calendar.day(d).isEmpty()) {
        d = d.plusDays(1);
      }
      return d;
    });
    when(h.accounts.activeAccountId(anyLong())).thenReturn(1L);
  }

  /** Stubs every contract double with {@code full} (true) or all-null (false) samples. */
  void stub(boolean full) {
    when(h.me.me(any())).thenReturn((MeDto) sample(MeDto.class, full));
    when(h.rateLimits.remaining(anyLong())).thenReturn(new RateLimitStatus(100, 19, 299, TestTimes.START));
    when(h.accounts.account(anyLong())).thenReturn((AccountDto) sample(AccountDto.class, full));
    when(h.accounts.positions(anyLong())).thenReturn(List.of((PositionDto) sample(PositionDto.class, full)));
    when(h.accounts.activities(anyLong(), any(), any(), anyInt()))
        .thenReturn(page((ActivityDto) sample(ActivityDto.class, full)));
    when(h.accounts.violations(anyLong())).thenReturn(List.of((ViolationDto) sample(ViolationDto.class, full)));
    when(h.accounts.runs(anyLong())).thenReturn(List.of((RunDto) sample(RunDto.class, full)));
    when(h.orders.list(anyLong(), any(), any(), anyInt())).thenReturn(page((OrderDto) sample(OrderDto.class, full)));
    when(h.orders.byPublicId(anyLong(), anyString(), anyBoolean()))
        .thenReturn(Optional.of((OrderDto) sample(OrderDto.class, full)));
    when(h.orders.executions(anyLong(), any(), any(), anyInt()))
        .thenReturn(page((ExecutionDto) sample(ExecutionDto.class, full)));
    when(h.performance.history(anyLong(), any()))
        .thenReturn((PortfolioHistoryDto) sample(PortfolioHistoryDto.class, full));
    when(h.performance.profile(anyString(), any()))
        .thenReturn((PlayerProfileDto) sample(PlayerProfileDto.class, full));
    when(h.performance.publicHistory(anyString(), any(), any()))
        .thenReturn((PublicHistoryDto) sample(PublicHistoryDto.class, full));
    when(h.performance.publicTrades(anyString(), any(), anyInt()))
        .thenReturn(page((PublicTradeDto) sample(PublicTradeDto.class, full)));
    when(h.performance.feed(any(), anyInt())).thenReturn(page((FeedItemDto) sample(FeedItemDto.class, full)));
    when(h.leaderboard.board(any(), any(), any(), anyInt(), any()))
        .thenReturn((LeaderboardDto) sample(LeaderboardDto.class, full));
    when(h.players.all(any(), anyBoolean()))
        .thenReturn(List.of((PlayerSummaryDto) sample(PlayerSummaryDto.class, full)));
    when(h.watchlists.list(anyLong())).thenReturn(List.of((WatchlistDto) sample(WatchlistDto.class, full)));
    when(h.orderService.preview(any(), any())).thenReturn((PreviewDto) sample(PreviewDto.class, full));
    h.notifications.notify(h.botPlayer.id(), com.stocks.common.domain.NotificationKind.FILL, "Bought 1 AAPL", "Filled.",
        full ? Map.of("orderId", "ord_1", "amount", new BigDecimal("1.00")) : null, "REF:" + full);
    // a price alert: with every optional member (note, expiry) or none of them
    h.alerts.create(h.botPlayer.id(), new com.stocks.contract.alerts.dto.CreateAlertRequest("AAPL",
        com.stocks.common.domain.AlertCondition.PRICE_GTE, new BigDecimal("230.00"), full,
        full ? TestTimes.START.plus(java.time.Duration.ofDays(7)) : null, full ? "breakout" : null));
  }

  static Object sample(Class<?> type, boolean full) {
    return full ? Samples.full(type) : Samples.nulls(type);
  }

  static <T> Page<T> page(T item) {
    return new Page<>(List.of(item), "c1");
  }

  /** The arguments each read tool is called with (§20.3 inputs). */
  static Map<String, Object> args(String tool) {
    return switch (tool) {
      case "get_calendar" -> Map.of("start", "2026-10-09", "end", "2026-12-01");
      case "search_symbols" -> Map.of("query", "AA");
      case "get_asset", "get_snapshot", "get_fundamentals" -> Map.of("symbol", "AAPL");
      case "get_quotes" -> Map.of("symbols", List.of("AAPL", "SPY", "NOPE"));
      case "get_bars" -> Map.of("symbol", "AAPL", "timeframe", "1Min", "limit", 10);
      case "get_movers" -> Map.of("list", "GAINERS");
      case "get_news" -> Map.of("symbols", List.of("AAPL"));
      case "get_order" -> Map.of("order_id", "ord_1");
      case "get_portfolio_history" -> Map.of("range", "1M");
      case "preview_order" -> Map.of("symbol", "AAPL", "side", "BUY", "type", "MARKET", "notional", "125.00");
      case "get_player_profile", "get_player_trades" -> Map.of("handle", "alice");
      case "get_player_history" -> Map.of("handle", "alice", "range", "1W");
      default -> Map.of();
    };
  }

  static final List<String> READ_TOOLS = List.of("whoami", "get_rules", "get_market_clock", "get_calendar",
      "search_symbols", "get_asset", "get_quotes", "get_snapshot", "get_bars", "get_movers", "get_news",
      "get_fundamentals", "get_account", "get_positions", "get_orders", "get_order", "get_executions", "get_activity",
      "get_violations", "get_runs", "get_portfolio_history", "get_notifications", "preview_order", "get_leaderboard",
      "list_players", "get_player_profile", "get_player_history", "get_player_trades", "get_league_feed",
      "get_watchlists", "list_alerts");

  @ParameterizedTest(name = "full={0}")
  @ValueSource(booleans = {true, false})
  void everyReadToolResultValidatesAgainstItsOutputSchema(boolean full) {
    assertThat(READ_TOOLS).hasSize(31).doesNotHaveDuplicates();
    stub(full);
    List<String> failures = new ArrayList<>();
    for (String tool : READ_TOOLS) {
      CallToolResult r = h.call(tool, args(tool));
      if (Boolean.TRUE.equals(r.isError())) {
        failures.add(tool + ": " + McpToolHarness.text(r));
        continue;
      }
      JsonSchemaValidator.ValidationResponse v = VALIDATOR.validate(h.tool(tool).outputSchema(),
          r.structuredContent());
      if (!v.valid()) {
        failures.add(tool + ": " + v.errorMessage());
      }
      JsonNode structured = McpToolHarness.MAPPER.valueToTree(r.structuredContent());
      List<String> floats = new ArrayList<>();
      floatingNumbers(structured, "$", floats);
      if (!floats.isEmpty()) {
        failures.add(tool + ": floating-point numbers at " + floats);
      }
      if (!McpToolHarness.MAPPER.readTree(McpToolHarness.text(r)).equals(structured)) {
        failures.add(tool + ": the text block differs from structuredContent");
      }
    }
    assertThat(failures).isEmpty();
  }

  @ParameterizedTest(name = "full={0}")
  @ValueSource(booleans = {true, false})
  void nullMembersArePresentNotDropped(boolean full) {
    stub(full);
    JsonNode account = McpToolHarness.ok(h.call("get_account", Map.of()));
    assertThat(account.has("restrictionUntil")).isTrue();
    if (!full) {
      assertThat(account.get("equity").isNull()).isTrue();
    } else {
      assertThat(account.get("equity").asString()).isEqualTo("12.3400");
    }
    JsonNode quotes = McpToolHarness.ok(h.call("get_quotes", Map.of("symbols", List.of("AAPL"))));
    JsonNode aapl = quotes.get("quotes").get(0);
    assertThat(aapl.get("bid").asString()).isEqualTo("227.39");
    assertThat(aapl.has("extended")).isTrue();
    assertThat(aapl.get("extended").isNull()).isTrue();
  }

  /** Paths of JSON numbers that are not integers (a decimal must be a string, §6.3). */
  static void floatingNumbers(JsonNode node, String path, List<String> found) {
    if (node.isNumber() && !node.isIntegralNumber()) {
      found.add(path);
    } else if (node.isObject()) {
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        floatingNumbers(e.getValue(), path + "." + e.getKey(), found);
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        floatingNumbers(node.get(i), path + "[" + i + "]", found);
      }
    }
  }

}
