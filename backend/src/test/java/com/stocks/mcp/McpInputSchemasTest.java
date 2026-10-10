package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * §20.2 input schemas, judged by the MCP SDK's own validator (the server validates every call's arguments against
 * them before a tool runs): decimals pass as strings or numbers, wire-value enums by their wire value, and a wrong
 * type is refused.
 */
class McpInputSchemasTest {

  static McpToolHarness harness;
  static final JsonSchemaValidator VALIDATOR = McpJsonDefaults.getSchemaValidator();

  @BeforeAll
  static void build() {
    harness = new McpToolHarness();
  }

  static boolean valid(Map<String, Object> schema, Map<String, Object> args) {
    return VALIDATOR.validate(schema, args).valid();
  }

  @Test
  void decimalsAreStringsOrNumbers() {
    Map<String, Object> schema = harness.tool("place_order").inputSchema();
    Map<String, Object> base = Map.of("client_order_id", "c-1", "symbol", "AAPL", "side", "BUY", "type", "LIMIT",
        "limit_price", "227.40");
    assertThat(valid(schema, base)).isTrue();
    assertThat(valid(schema, Map.of("client_order_id", "c-1", "symbol", "AAPL", "side", "BUY", "type", "MARKET",
        "notional", new BigDecimal("125.00")))).isTrue();
    assertThat(valid(schema, Map.of("client_order_id", "c-1", "symbol", "AAPL", "side", "BUY", "type", "MARKET",
        "qty", 3))).isTrue();
    assertThat(valid(schema, Map.of("client_order_id", "c-1", "symbol", "AAPL", "side", "BUY", "type", "MARKET",
        "qty", true))).as("a boolean is not a decimal").isFalse();
    assertThat(valid(schema, Map.of("symbol", "AAPL", "side", "BUY", "type", "MARKET"))).as("client_order_id")
        .isFalse();
    assertThat(valid(schema, Map.of("client_order_id", "c-1", "symbol", "AAPL", "side", "HOLD", "type", "MARKET")))
        .as("unknown side").isFalse();
  }

  @Test
  void wireValueEnumsAreAdvertisedByWireValue() {
    Map<String, Object> bars = harness.tool("get_bars").inputSchema();
    assertThat(valid(bars, Map.of("symbol", "AAPL", "timeframe", "1Min"))).isTrue();
    assertThat(valid(bars, Map.of("symbol", "AAPL", "timeframe", "MIN1"))).isFalse();
    Map<String, Object> history = harness.tool("get_portfolio_history").inputSchema();
    assertThat(valid(history, Map.of("range", "1D"))).isTrue();
    assertThat(valid(history, Map.of("range", "D1"))).isFalse();
  }

  @Test
  void listsOfEnumsAndOptionalArguments() {
    Map<String, Object> activity = harness.tool("get_activity").inputSchema();
    assertThat(valid(activity, Map.of())).isTrue();
    assertThat(valid(activity, Map.of("types", List.of("FILL", "DEPOSIT"), "from", "2026-10-01"))).isTrue();
    assertThat(valid(activity, Map.of("types", List.of("NOPE")))).isFalse();
    assertThat(valid(harness.tool("whoami").inputSchema(), Map.of())).isTrue();
  }
}
