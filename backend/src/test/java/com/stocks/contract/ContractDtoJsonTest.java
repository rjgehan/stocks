package com.stocks.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.config.JacksonConfig;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * §18.3 / §21.1 DTO contract (§26 "Contract" layer): every DTO the spec names exists under the naming rule, and
 * every record in {@code com.stocks.contract..dto} serializes per §6.3 (decimals as plain strings, nulls present, no
 * floating-point numbers) and round-trips through the same mapper.
 */
class ContractDtoJsonTest {

  private static final JsonMapper MAPPER = JacksonConfig.newMapper();

  /** §18.3 and §21.1 type name → Java class (naming rule: {@code T} → {@code TDto}; Request/Response keep their name). */
  static final Map<String, String> SPEC_TYPES = new LinkedHashMap<>();

  static {
    String id = "com.stocks.contract.identity.dto.";
    String md = "com.stocks.contract.marketdata.dto.";
    String tr = "com.stocks.contract.trading.dto.";
    String pf = "com.stocks.contract.portfolio.dto.";
    String ad = "com.stocks.contract.admin.dto.";
    // §18.3 shared
    SPEC_TYPES.put("Page", "com.stocks.common.web.Page");
    SPEC_TYPES.put("Warning", "com.stocks.common.error.Warning");
    SPEC_TYPES.put("Fees", tr + "FeesDto");
    // identity
    SPEC_TYPES.put("Preferences", id + "PreferencesDto");
    SPEC_TYPES.put("Me", id + "MeDto");
    SPEC_TYPES.put("ApiKeyInfo", id + "ApiKeyInfoDto");
    SPEC_TYPES.put("ApiKeyCreated", id + "ApiKeyCreatedDto");
    SPEC_TYPES.put("PlayerSummary", id + "PlayerSummaryDto");
    SPEC_TYPES.put("Notification", "com.stocks.contract.realtime.dto.NotificationDto");
    // market
    SPEC_TYPES.put("Clock", md + "ClockDto");
    SPEC_TYPES.put("TradingDay", md + "TradingDayDto");
    SPEC_TYPES.put("AssetSummary", md + "AssetSummaryDto");
    SPEC_TYPES.put("Asset", md + "AssetDto");
    SPEC_TYPES.put("Fundamentals", md + "FundamentalsDto");
    SPEC_TYPES.put("ExtendedLine", md + "ExtendedLineDto");
    SPEC_TYPES.put("Quote", md + "QuoteDto");
    SPEC_TYPES.put("Bar", md + "BarDto");
    SPEC_TYPES.put("Snapshot", md + "SnapshotDto");
    SPEC_TYPES.put("Bars", md + "BarsDto");
    SPEC_TYPES.put("Movers", md + "MoversDto");
    SPEC_TYPES.put("NewsItem", md + "NewsItemDto");
    // account
    SPEC_TYPES.put("Account", tr + "AccountDto");
    SPEC_TYPES.put("Position", tr + "PositionDto");
    SPEC_TYPES.put("Activity", tr + "ActivityDto");
    SPEC_TYPES.put("ActivityDetail", tr + "ActivityDetailDto");
    SPEC_TYPES.put("PortfolioHistory", pf + "PortfolioHistoryDto");
    SPEC_TYPES.put("Violation", tr + "ViolationDto");
    SPEC_TYPES.put("Run", tr + "RunDto");
    // orders
    SPEC_TYPES.put("PlaceOrderRequest", tr + "PlaceOrderRequest");
    SPEC_TYPES.put("ReplaceOrderRequest", tr + "ReplaceOrderRequest");
    SPEC_TYPES.put("Execution", tr + "ExecutionDto");
    SPEC_TYPES.put("OrderEvent", tr + "OrderEventDto");
    SPEC_TYPES.put("Order", tr + "OrderDto");
    SPEC_TYPES.put("Estimate", tr + "EstimateDto");
    SPEC_TYPES.put("PlaceOrderResponse", tr + "PlaceOrderResponse");
    SPEC_TYPES.put("Preview", tr + "PreviewDto");
    SPEC_TYPES.put("Watchlist", "com.stocks.contract.watchlist.dto.WatchlistDto");
    // league
    SPEC_TYPES.put("LeaderboardRow", pf + "LeaderboardRowDto");
    SPEC_TYPES.put("Leaderboard", pf + "LeaderboardDto");
    SPEC_TYPES.put("PublicTrade", pf + "PublicTradeDto");
    SPEC_TYPES.put("PlayerProfile", pf + "PlayerProfileDto");
    SPEC_TYPES.put("PublicHistory", pf + "PublicHistoryDto");
    SPEC_TYPES.put("FeedItem", pf + "FeedItemDto");
    // league, Phase 2 (§16.8–16.11)
    SPEC_TYPES.put("PlayerStats", pf + "PlayerStatsDto");
    SPEC_TYPES.put("ClosedLot", pf + "PlayerStatsDto$ClosedLotDto");
    SPEC_TYPES.put("RankPoint", pf + "PlayerStatsDto$RankPointDto");
    SPEC_TYPES.put("RaceChart", pf + "RaceChartDto");
    SPEC_TYPES.put("RaceSeries", pf + "RaceChartDto$RaceSeriesDto");
    SPEC_TYPES.put("RacePoint", pf + "RaceChartDto$RacePointDto");
    SPEC_TYPES.put("BenchmarkCurve", pf + "BenchmarkCurveDto");
    SPEC_TYPES.put("PnlAnalysis", pf + "PnlAnalysisDto");
    SPEC_TYPES.put("EarningsPoint", pf + "PnlAnalysisDto$EarningsPointDto");
    SPEC_TYPES.put("SymbolPnl", pf + "PnlAnalysisDto$SymbolPnlDto");
    SPEC_TYPES.put("PnlCalendar", pf + "PnlCalendarDto");
    SPEC_TYPES.put("PnlCalendarCell", pf + "PnlCalendarDto$PnlCalendarCellDto");
    SPEC_TYPES.put("PnlCalendarTotal", pf + "PnlCalendarDto$PnlCalendarTotalDto");
    SPEC_TYPES.put("FeedReactions", pf + "FeedReactionsDto");
    SPEC_TYPES.put("FeedReaction", pf + "FeedReactionsDto$FeedReactionDto");
    SPEC_TYPES.put("Reactor", pf + "FeedReactionsDto$ReactorDto");
    SPEC_TYPES.put("SetReactionRequest", pf + "SetReactionRequest");
    // public mode (§16.7)
    SPEC_TYPES.put("PublicPortfolio", pf + "PublicPortfolioDto");
    SPEC_TYPES.put("PublicOrder", pf + "PublicOrderDto");
    SPEC_TYPES.put("PublicFill", pf + "PublicOrderDto$PublicFillDto");
    // rules
    SPEC_TYPES.put("Rules", "com.stocks.contract.rules.dto.RulesDto");
    // §21.1 admin
    SPEC_TYPES.put("AdminPlayer", ad + "AdminPlayerDto");
    SPEC_TYPES.put("CreatePlayerRequest", ad + "CreatePlayerRequest");
    SPEC_TYPES.put("CreatePlayerResponse", ad + "CreatePlayerResponse");
    SPEC_TYPES.put("AdminPlayerDetail", ad + "AdminPlayerDetailDto");
    SPEC_TYPES.put("CalendarOverride", "com.stocks.contract.calendar.dto.CalendarOverrideDto");
    SPEC_TYPES.put("CorporateAction", tr + "CorporateActionDto");
    SPEC_TYPES.put("CashTransferRequest", tr + "CashTransferRequest");
    SPEC_TYPES.put("CashTransferPreview", tr + "CashTransferPreviewDto");
    SPEC_TYPES.put("TransferBatchResult", tr + "TransferBatchResultDto");
    SPEC_TYPES.put("TransferRow", tr + "TransferRowDto");
    SPEC_TYPES.put("Settings", ad + "SettingsDto");
    SPEC_TYPES.put("AuditRow", ad + "AuditRowDto");
    SPEC_TYPES.put("SystemStatus", ad + "SystemStatusDto");
    // §19 STOMP payloads
    SPEC_TYPES.put("tick", md + "TickDto");
    SPEC_TYPES.put("market.session", "com.stocks.contract.realtime.dto.MarketSessionDto");
    SPEC_TYPES.put("leaderboard", "com.stocks.contract.realtime.dto.LeaderboardUpdateDto");
    SPEC_TYPES.put("queue/orders", "com.stocks.contract.realtime.dto.OrderUpdateDto");
    SPEC_TYPES.put("queue/admin", "com.stocks.contract.realtime.dto.AdminAlertDto");
  }

  static Stream<Class<?>> records() {
    return DtoSamples.dtoRecords().stream();
  }

  @Test
  void everySpecTypeExistsUnderTheNamingRule() throws Exception {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> e : SPEC_TYPES.entrySet()) {
      try {
        Class<?> c = Class.forName(e.getValue());
        assertThat(c.isRecord() || c.isSealed()).as(e.getKey() + " is a record or sealed union").isTrue();
      } catch (ClassNotFoundException ex) {
        missing.add(e.getKey() + " -> " + e.getValue());
      }
    }
    assertThat(missing).as("§18.3/§21.1/§19 types without a Java record").isEmpty();
  }

  @Test
  void dtoPackagesHoldOnlyRecordsSealedUnionsAndEnums() {
    List<Class<?>> types = DtoSamples.dtoTypes();
    assertThat(types).hasSizeGreaterThan(100);
    for (Class<?> c : types) {
      assertThat(c.isRecord() || c.isEnum() || (c.isInterface() && c.isSealed()))
          .as(c.getName() + " is a record, an enum or a sealed interface").isTrue();
      if (c.isRecord() && !c.getSimpleName().endsWith("Request") && !c.getSimpleName().endsWith("Response")) {
        assertThat(c.getSimpleName()).as("naming rule §18.3").endsWith("Dto");
      }
    }
  }

  @Test
  void recordSimpleNamesAreUniqueSoOpenApiSchemaNamesCannotCollide() {
    Map<String, String> seen = new LinkedHashMap<>();
    for (Class<?> c : DtoSamples.dtoTypes()) {
      if (c.isEnum()) {
        continue;
      }
      String prev = seen.put(c.getSimpleName(), c.getName());
      assertThat(prev).as("duplicate simple name " + c.getSimpleName()).isNull();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("records")
  void nullsArePresentWithEveryProperty(Class<?> record) throws Exception {
    Object value = DtoSamples.allNull(record);
    JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(value));

    List<String> expected = new ArrayList<>();
    String disc = DtoSamples.discriminator(record);
    if (disc != null) {
      expected.add(disc);
    }
    expected.addAll(DtoSamples.jsonNames(record));
    assertThat(List.copyOf(node.propertyNames())).as("properties of " + record.getSimpleName())
        .containsExactlyElementsOf(expected);

    for (RecordComponent rc : record.getRecordComponents()) {
      if (!rc.getType().isPrimitive()) {
        assertThat(node.get(DtoSamples.jsonName(rc)).isNull()).as(rc.getName() + " is a JSON null").isTrue();
      }
    }
    assertThat(MAPPER.readValue(MAPPER.writeValueAsString(value), record)).isEqualTo(value);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("records")
  void decimalsAreStringsAndSamplesRoundTrip(Class<?> record) throws Exception {
    Object value = DtoSamples.sample(record);
    String json = MAPPER.writeValueAsString(value);
    JsonNode node = MAPPER.readTree(json);

    for (RecordComponent rc : record.getRecordComponents()) {
      JsonNode v = node.get(DtoSamples.jsonName(rc));
      if (rc.getType() == BigDecimal.class) {
        assertThat(v.isString()).as(rc.getName() + " is a JSON string").isTrue();
        assertThat(v.asString()).isEqualTo(DtoSamples.SAMPLE_DECIMAL_JSON);
      }
      if (isListOfDecimals(rc.getGenericType())) {
        assertThat(v.isArray()).isTrue();
        v.values().forEach(e -> assertThat(e.isString()).as(rc.getName() + "[] are JSON strings").isTrue());
      }
    }
    assertNoFloatingPoint(node, record.getSimpleName());
    assertThat(json).doesNotContain("E+").doesNotContain("E-");
    assertThat(MAPPER.readValue(json, record)).as("round trip of " + record.getSimpleName()).isEqualTo(value);
  }

  private static boolean isListOfDecimals(Type t) {
    return t instanceof ParameterizedType pt && pt.getRawType() == List.class
        && pt.getActualTypeArguments()[0] == BigDecimal.class;
  }

  static void assertNoFloatingPoint(JsonNode node, String path) {
    assertThat(node.isFloatingPointNumber()).as("floating-point number at " + path).isFalse();
    if (node.isObject()) {
      node.properties().forEach(e -> assertNoFloatingPoint(e.getValue(), path + "." + e.getKey()));
    } else if (node.isArray()) {
      int i = 0;
      for (JsonNode child : node.values()) {
        assertNoFloatingPoint(child, path + "[" + i++ + "]");
      }
    }
  }
}
