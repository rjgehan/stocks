package com.stocks.trading.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * §11.2 checks 9–26 for STOP, STOP_LIMIT and TRAILING_STOP through the real {@code OrderService} (gate on the request
 * thread, validator on the engine thread): every stop-specific error code, the row order of the checks, holds and
 * estimates, and previews. Synchronous rejections are never persisted (D15).
 */
class StopOrderValidationTest extends TradingTestBase {

  @Test
  void pricesRequiredAndAllowedByType() {
    Player p = human();
    String s = priced();
    expect(ErrorCode.STOP_PRICE_REQUIRED, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null,
        null, null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.LIMIT_PRICE_REQUIRED, "limitPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP_LIMIT, "1",
        null, null, "105.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.STOP_PRICE_REQUIRED, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP_LIMIT, "1",
        null, "105.50", null, null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.TRAIL_REQUIRED, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.TRAIL_REQUIRED, "trailPercent", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, "1.00", "5", TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, "limitPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null,
        "105.50", "105.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, "105.00", "1.00", null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null,
        null, "105.00", "1.00", null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, "trailPercent", () -> place(p, req(s, Side.BUY, OrderType.LIMIT, "1", null,
        "99.00", null, null, "5", TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.MARKET, "1", null,
        null, "105.00", null, null, TimeInForce.DAY, false)));
    assertNothingPersisted(p);
  }

  @Test
  void ticksRangesExtendedHoursAndTimeInForce() {
    Player p = human();
    String s = priced();
    expect(ErrorCode.INVALID_TICK, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null,
        "105.005", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.INVALID_TICK, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1", null,
        null, null, "0.005", null, TimeInForce.DAY, false)));
    expect(ErrorCode.VALIDATION_FAILED, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null,
        "-1.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.VALIDATION_FAILED, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, "0", null, TimeInForce.DAY, false)));
    for (String pct : new String[] {"0", "100", "-5", "5.12345"}) {
      expect(ErrorCode.VALIDATION_FAILED, "trailPercent", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP,
          "1", null, null, null, null, pct, TimeInForce.DAY, false)));
    }
    expect(ErrorCode.EXT_HOURS_LIMIT_ONLY, "extendedHours", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1",
        null, null, "105.00", null, null, TimeInForce.DAY, true)));
    expect(ErrorCode.EXT_HOURS_LIMIT_ONLY, "extendedHours", () -> place(p, req(s, Side.BUY, OrderType.STOP_LIMIT,
        "1", null, "105.50", "105.00", null, null, TimeInForce.GTC, true)));
    expect(ErrorCode.TIF_NOT_ALLOWED, "timeInForce", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, "5", TimeInForce.GTC, false)));
    expect(ErrorCode.FRACTIONAL_MARKET_ONLY, "type", () -> place(p, req(s, Side.BUY, OrderType.STOP, "0.5", null,
        null, "105.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.FRACTIONAL_MARKET_ONLY, "type", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, null,
        "50.00", null, null, null, "5", TimeInForce.DAY, false)));
    assertNothingPersisted(p);
  }

  @Test
  void stopsSitOnTheRightSideOfTheMarketAndNotTooFarFromIt() {
    Player p = human("5000.00");
    String s = priced();
    expect(ErrorCode.STOP_PRICE_WRONG_SIDE, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null,
        null, "99.99", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.STOP_PRICE_WRONG_SIDE, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP_LIMIT, "1",
        null, "101.00", "100.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_TOO_FAR, "stopPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null,
        "150.01", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_TOO_FAR, "limitPrice", () -> place(p, req(s, Side.BUY, OrderType.STOP_LIMIT, "1", null,
        "160.00", "105.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_TOO_FAR, "trailPercent", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, "60", TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_TOO_FAR, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, "51.00", null, TimeInForce.DAY, false)));
    assertNothingPersisted(p);

    PlaceOrderResponse far = place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null, "110.00", null, null,
        TimeInForce.DAY, false));
    assertThat(far.warnings()).extracting(w -> w.code()).contains("FAT_FINGER");

    Player h = human("5000.00");
    String t = symbol();
    print(t, "100.00", 100);
    quote(t, "99.98", 1000, "100.00", 1000);
    OrderDto buy = place(h, market(cid(), t, Side.BUY, "10")).order();
    time.advance(Duration.ofSeconds(1));
    quote(t, "99.98", 1000, "100.00", 1000);
    step();
    assertThat(order(h, buy.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    expect(ErrorCode.STOP_PRICE_WRONG_SIDE, "stopPrice", () -> place(h, req(t, Side.SELL, OrderType.STOP, "1", null,
        null, "100.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.INSUFFICIENT_POSITION, "qty", () -> place(h, req(t, Side.SELL, OrderType.STOP, "11", null, null,
        "95.00", null, null, TimeInForce.DAY, false)));
    PlaceOrderResponse sell = place(h, req(t, Side.SELL, OrderType.TRAILING_STOP, "10", null, null, null, null, "5",
        TimeInForce.DAY, false));
    assertThat(sell.order().reservedQty()).isEqualByComparingTo("10");
    expect(ErrorCode.INSUFFICIENT_POSITION, "qty", () -> place(h, req(t, Side.SELL, OrderType.STOP, "1", null, null,
        "95.00", null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.SHORT_SELLING_NOT_ALLOWED, "side", () -> place(p, req(s, Side.SELL, OrderType.STOP, "1", null,
        null, "95.00", null, null, TimeInForce.DAY, false)));
  }

  @Test
  void trailingStopsAreEnteredInRegularHoursOnlyButEarlierChecksWin() {
    Player p = human();
    String s = priced();
    time.set(et("2026-10-09T17:00:00"));
    expect(ErrorCode.SESSION_NOT_ALLOWED, null, () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1", null,
        null, null, null, "5", TimeInForce.DAY, false)));
    // check 15 (TRAIL_REQUIRED) and 18's TIF part run before the session part of check 18
    expect(ErrorCode.TRAIL_REQUIRED, "trailAmount", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, null, TimeInForce.DAY, false)));
    expect(ErrorCode.TIF_NOT_ALLOWED, "timeInForce", () -> place(p, req(s, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, "5", TimeInForce.GTC, false)));
    PreviewDto preview = orders.preview(p.principal(), req(s, Side.BUY, OrderType.TRAILING_STOP, "1", null, null,
        null, null, "5", TimeInForce.DAY, false));
    assertThat(preview.valid()).isFalse();
    assertThat(preview.errors()).extracting(e -> e.code()).containsExactly("SESSION_NOT_ALLOWED");
    assertThat(preview.sessionNote()).isNull();
    // a plain stop entered at the same time queues for the open
    PlaceOrderResponse queued = place(p, req(s, Side.BUY, OrderType.STOP, "1", null, null, "105.00", null, null,
        TimeInForce.GTC, false));
    assertThat(queued.order().waitReason()).isEqualTo(com.stocks.common.domain.WaitReason.QUEUED_FOR_OPEN);
    assertThat(queued.warnings()).extracting(w -> w.code()).contains("QUEUED_FOR_OPEN", "WILL_PAUSE_OUTSIDE_SESSION");
  }

  @Test
  void holdsEstimatesAndBuyingPower() {
    Player p = human();
    String s = priced();
    expect(ErrorCode.INSUFFICIENT_BUYING_POWER, "qty", () -> place(p, req(s, Side.BUY, OrderType.STOP, "5", null,
        null, "105.00", null, null, TimeInForce.DAY, false)));
    ApiException bp = catchApi(() -> place(p, req(s, Side.BUY, OrderType.STOP, "5", null, null, "105.00", null, null,
        TimeInForce.DAY, false)));
    assertThat(bp.hint()).contains("Max 4 whole shares at $107.10");

    PreviewDto stopLimit = orders.preview(p.principal(), req(s, Side.BUY, OrderType.STOP_LIMIT, "4", null, "106.00",
        "105.00", null, null, TimeInForce.GTC, false));
    assertThat(stopLimit.valid()).isTrue();
    assertThat(stopLimit.estimate().hold()).isEqualByComparingTo("424.00");
    assertThat(stopLimit.estimate().price()).isEqualByComparingTo("105.00");
    assertThat(stopLimit.buyingPowerAfter()).isEqualByComparingTo("76.00");
    assertThat(stopLimit.sessionNote()).isNull();

    PreviewDto trail = orders.preview(p.principal(), req(s, Side.BUY, OrderType.TRAILING_STOP, "2", null, null, null,
        "2.50", null, TimeInForce.DAY, false));
    assertThat(trail.valid()).isTrue();
    // 2 × (100 + 2.50) × 1.02 = 209.10; the starting stop TICK_UP(100 + 2.50)
    assertThat(trail.estimate().hold()).isEqualByComparingTo("209.10");
    assertThat(trail.estimate().price()).isEqualByComparingTo("102.50");

    // no price at all: a stop holds against its own stop price, a trailing stop cannot be sized
    String dark = symbol();
    PlaceOrderResponse blind = place(p, req(dark, Side.BUY, OrderType.STOP, "1", null, null, "20.00", null, null,
        TimeInForce.DAY, false));
    assertThat(blind.order().reservedCash()).isEqualByComparingTo("20.40");
    expect(ErrorCode.MARKET_DATA_UNAVAILABLE, null, () -> place(p, req(dark, Side.BUY, OrderType.TRAILING_STOP, "1",
        null, null, null, null, "5", TimeInForce.DAY, false)));
  }

  @Test
  void anAiPlayerNeedsARationaleForStopsToo() {
    Player bot = bot();
    String s = priced();
    expect(ErrorCode.RATIONALE_REQUIRED, "rationale", () -> orders.place(bot.principal(), new PlaceOrderRequest(cid(),
        s, Side.BUY, OrderType.STOP, BigDecimal.ONE, null, null, new BigDecimal("105.00"), null, null,
        TimeInForce.DAY, false, null, null, Set.of()), botMeta()));
    PreviewDto preview = orders.preview(bot.principal(), new PlaceOrderRequest(null, s, Side.BUY, OrderType.STOP,
        BigDecimal.ONE, null, null, new BigDecimal("105.00"), null, null, TimeInForce.DAY, false, null, null,
        Set.of()));
    assertThat(preview.valid()).as("a preview needs no rationale (§20.3 preview_order)").isTrue();
    PlaceOrderResponse ok = orders.place(bot.principal(), new PlaceOrderRequest(cid(), s, Side.BUY,
        OrderType.STOP_LIMIT, BigDecimal.ONE, null, new BigDecimal("105.50"), new BigDecimal("105.00"), null, null,
        TimeInForce.GTC, false, "Breakout entry above resistance.", null, Set.of()), botMeta());
    assertThat(ok.order().status()).isEqualTo(OrderStatus.PENDING);
    assertThat(ok.order().rationale()).isEqualTo("Breakout entry above resistance.");
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** A symbol with a $100.00 last trade and a 99.98 × 100.00 quote. */
  private String priced() {
    String s = symbol();
    print(s, "100.00", 100);
    quote(s, "99.98", 1000, "100.00", 1000);
    return s;
  }

  private static PlaceOrderRequest req(String s, Side side, OrderType type, String qty, String notional, String limit,
                                       String stop, String amount, String pct, TimeInForce tif, boolean ext) {
    return new PlaceOrderRequest(cid(), s, side, type, dec(qty), dec(notional), dec(limit), dec(stop), dec(amount),
        dec(pct), tif, ext, "test", null, Set.of());
  }

  private static BigDecimal dec(String v) {
    return v == null ? null : new BigDecimal(v);
  }

  private void assertNothingPersisted(Player p) {
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ?").param(p.accountId())
        .query(Integer.class).single()).as("synchronous rejections are not persisted (D15)").isZero();
  }

  private static ApiException catchApi(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e;
    }
    throw new AssertionError("expected an ApiException");
  }

  private static void expect(ErrorCode code, String field, Runnable call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, (Consumer<ApiException>) e -> {
      assertThat(e.code()).as(e.detail()).isEqualTo(code);
      if (field != null) {
        assertThat(e.fields()).as("field of %s", code).isNotEmpty();
        assertThat(e.fields().get(0).field()).isEqualTo(field);
      }
    });
  }
}
