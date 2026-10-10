package com.stocks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.ChartRange;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.error.Problem;
import com.stocks.common.error.ProblemField;
import com.stocks.common.web.Page;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** §6.3 JSON rules: decimals as plain strings, nulls included, ISO instants with millis, enum handling. */
class JacksonConfigTest {

  private final JsonMapper mapper = JacksonConfig.newMapper();

  record Sample(BigDecimal price, BigDecimal qty, BigDecimal cash, BigDecimal big, BigDecimal tiny, BigDecimal limitPrice,
                Instant at, LocalDate tradeDate, Side side, Timeframe timeframe, ChartRange range, Duration delay,
                Long volume, int count) {}

  record Order(Side side, OrderType type, BigDecimal qty, BigDecimal notional, BigDecimal limitPrice,
               Timeframe timeframe) {}

  @Test
  void decimalsAreStringsInPlainNotationAndNullsArePresent() throws Exception {
    Sample s = new Sample(new BigDecimal("227.4000"), new BigDecimal("2.19414"), new BigDecimal("500.00"),
        new BigDecimal("1E+3"), new BigDecimal("1E-7"), null, Instant.parse("2026-10-09T14:00:00Z"),
        LocalDate.of(2026, 10, 13), Side.BUY, Timeframe.MIN1, ChartRange.D5, Duration.ofMinutes(15), 1200L, 3);
    String json = mapper.writeValueAsString(s);
    JsonNode n = mapper.readTree(json);

    assertThat(n.get("price").isString()).isTrue();
    assertThat(n.get("price").asString()).isEqualTo("227.4000");
    assertThat(n.get("qty").asString()).isEqualTo("2.19414");
    assertThat(n.get("cash").asString()).isEqualTo("500.00");
    assertThat(n.get("big").asString()).isEqualTo("1000");
    assertThat(n.get("tiny").asString()).isEqualTo("0.0000001");
    assertThat(json).doesNotContain("E+").doesNotContain("E-");
    assertThat(n.has("limitPrice")).isTrue();
    assertThat(n.get("limitPrice").isNull()).isTrue();
    assertThat(n.get("at").asString()).isEqualTo("2026-10-09T14:00:00.000Z");
    assertThat(n.get("tradeDate").asString()).isEqualTo("2026-10-13");
    assertThat(n.get("side").asString()).isEqualTo("BUY");
    assertThat(n.get("timeframe").asString()).isEqualTo("1Min");
    assertThat(n.get("range").asString()).isEqualTo("5D");
    assertThat(n.get("delay").asString()).isEqualTo("PT15M");
    assertThat(n.get("volume").isIntegralNumber()).isTrue();
    assertThat(n.get("count").asInt()).isEqualTo(3);
  }

  @Test
  void instantsAlwaysCarryMillisecondsAndZ() throws Exception {
    assertThat(mapper.writeValueAsString(Instant.parse("2026-10-09T14:00:00.123456Z")))
        .isEqualTo("\"2026-10-09T14:00:00.123Z\"");
    assertThat(mapper.readValue("\"2026-10-09T14:00:00.123Z\"", Instant.class))
        .isEqualTo(Instant.parse("2026-10-09T14:00:00.123Z"));
    assertThat(mapper.readValue("\"2026-10-09T10:00:00-04:00\"", Instant.class))
        .isEqualTo(Instant.parse("2026-10-09T14:00:00Z"));
  }

  @Test
  void decimalInputAcceptsStringsAndNumbersWithoutDouble() throws Exception {
    Order fromNumbers = mapper.readValue(
        "{\"side\":\"buy\",\"type\":\"Limit\",\"qty\":0.1,\"notional\":null,\"limitPrice\":227.4,\"timeframe\":\"1day\"}",
        Order.class);
    assertThat(fromNumbers.side()).isEqualTo(Side.BUY);
    assertThat(fromNumbers.type()).isEqualTo(OrderType.LIMIT);
    assertThat(fromNumbers.qty()).isEqualTo(new BigDecimal("0.1"));          // not 0.1000000000000000055…
    assertThat(fromNumbers.limitPrice()).isEqualTo(new BigDecimal("227.4"));
    assertThat(fromNumbers.notional()).isNull();
    assertThat(fromNumbers.timeframe()).isEqualTo(Timeframe.DAY1);

    Order fromStrings = mapper.readValue(
        "{\"side\":\"SELL\",\"type\":\"MARKET\",\"qty\":\"0.00001\",\"limitPrice\":\"227.4000\"}", Order.class);
    assertThat(fromStrings.qty()).isEqualTo(new BigDecimal("0.00001"));
    assertThat(fromStrings.limitPrice()).isEqualTo(new BigDecimal("227.4000"));

    // untyped numbers (Map/Object targets) also stay BigDecimal
    Map<?, ?> untyped = mapper.readValue("{\"x\":227.41}", Map.class);
    assertThat(untyped.get("x")).isEqualTo(new BigDecimal("227.41"));
  }

  @Test
  void unknownEnumValuesFail() {
    assertThatThrownBy(() -> mapper.readValue("{\"side\":\"HOLD\"}", Order.class))
        .hasMessageContaining("HOLD");
    assertThatThrownBy(() -> mapper.readValue("{\"timeframe\":\"2Min\"}", Order.class))
        .hasMessageContaining("2Min");
  }

  @Test
  void problemAndPageShapes() throws Exception {
    JsonNode p = mapper.readTree(mapper.writeValueAsString(new Problem("/errors/VALIDATION_FAILED",
        "Validation failed", 400, "VALIDATION_FAILED", "bad", null, List.of(new ProblemField("qty", "INVALID_QTY", "x")),
        null, "req_1")));
    assertThat(p.propertyNames()).containsExactly("type", "title", "status", "code", "detail", "hint", "fields",
        "retryAfterS", "requestId");
    assertThat(p.get("hint").isNull()).isTrue();
    assertThat(p.get("retryAfterS").isNull()).isTrue();

    JsonNode page = mapper.readTree(mapper.writeValueAsString(new Page<>(List.of(BigDecimal.ONE), null)));
    assertThat(page.get("items").get(0).asString()).isEqualTo("1");
    assertThat(page.has("nextCursor")).isTrue();
    assertThat(page.get("nextCursor").isNull()).isTrue();
  }
}
