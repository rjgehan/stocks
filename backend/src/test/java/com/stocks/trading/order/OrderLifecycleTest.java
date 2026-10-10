package com.stocks.trading.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.engine.EngineCommand;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Order entry on the service layer (§11.2, §11.6–§11.8): idempotent {@code clientOrderId}, cancel (idempotent on a
 * cancelled order, 409 on a filled one), cancel-all, cancel-replace, close position, preview, and the validation rows
 * of the Phase 1 matrix (D7, D8, D15).
 */
class OrderLifecycleTest extends TradingTestBase {

  @Test
  void aRepeatedClientOrderIdReplaysAndADifferentRequestConflicts() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    String cid = cid();
    PlaceOrderResponse first = place(p, market(cid, s, Side.BUY, "2"));
    assertThat(first.replayed()).isFalse();
    PlaceOrderResponse again = place(p, new PlaceOrderRequest(cid, s.toLowerCase(), Side.BUY, OrderType.MARKET,
        new BigDecimal("2.000"), null, null, null, null, null, TimeInForce.DAY, false, "another rationale", null,
        Set.of()));
    assertThat(again.replayed()).isTrue();
    assertThat(again.order().orderId()).isEqualTo(first.order().orderId());
    assertThatThrownBy(() -> place(p, market(cid, s, Side.BUY, "3")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.CLIENT_ORDER_ID_CONFLICT);
          assertThat(e.status()).isEqualTo(409);
        });
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ?").param(p.accountId())
        .query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void anInvalidClientOrderIdIsRejected() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    expect(ErrorCode.CLIENT_ORDER_ID_INVALID, () -> place(p, market("has space", s, Side.BUY, "1")));
    expect(ErrorCode.CLIENT_ORDER_ID_INVALID, () -> place(p, market(null, s, Side.BUY, "1")));
  }

  @Test
  void cancelReleasesTheHoldAndIsIdempotent() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "2", "90.00", TimeInForce.GTC, false)).order();
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("320.00");
    OrderDto c = orders.cancel(p.principal(), o.orderId(), meta());
    assertThat(c.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(c.statusReason()).isEqualTo("USER");
    assertThat(c.reservedCash()).isEqualByComparingTo("0");
    assertThat(orders.cancel(p.principal(), o.orderId(), meta()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(accountQueries.balances(p.accountId(), TestTimes.TRADE_DATE).buyingPower())
        .isEqualByComparingTo("500.00");
    expect(ErrorCode.ORDER_NOT_FOUND, () -> orders.cancel(p.principal(), "ord_01NOPE", meta()));
    // another player's order is invisible
    Player other = human();
    expect(ErrorCode.ORDER_NOT_FOUND, () -> orders.cancel(other.principal(), o.orderId(), meta()));
  }

  @Test
  void aFilledOrderCannotBeCancelled() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    OrderDto o = place(p, market(cid(), s, Side.BUY, "1")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "9.99", 300, "10.00", 300);
    step();
    assertThat(order(p, o.orderId()).status()).isEqualTo(OrderStatus.FILLED);
    expect(ErrorCode.ORDER_NOT_CANCELLABLE, () -> orders.cancel(p.principal(), o.orderId(), meta()));
  }

  @Test
  void cancelAllCancelsEveryOpenOrderOrOneSymbol() {
    Player p = human("5000.00");
    String a = symbol();
    String b = symbol();
    quote(a, "9.99", 300, "10.00", 300);
    quote(b, "19.99", 300, "20.00", 300);
    place(p, limit(cid(), a, Side.BUY, "1", "9.50", TimeInForce.GTC, false));
    place(p, limit(cid(), a, Side.BUY, "1", "9.60", TimeInForce.GTC, false));
    place(p, limit(cid(), b, Side.BUY, "1", "19.50", TimeInForce.GTC, false));
    List<OrderDto> onA = orders.cancelAll(p.principal(), a.toLowerCase(), meta());
    assertThat(onA).hasSize(2).allMatch(o -> o.status() == OrderStatus.CANCELLED);
    assertThat(orderQueries.list(p.accountId(), new OrderFilter(OrderStatusFilter.OPEN, null, null, null), null, 50)
        .items()).singleElement().satisfies(o -> assertThat(o.symbol()).isEqualTo(b));
    assertThat(orders.cancelAll(p.principal(), null, meta())).hasSize(1);
    assertThat(orderQueries.list(p.accountId(), new OrderFilter(OrderStatusFilter.CLOSED, null, null, null), null,
        50).items()).hasSize(3);
  }

  @Test
  void replaceIsCancelReplaceWithTheOldHoldCreditedBack() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "4", "95.00", TimeInForce.DAY, false)).order();  // hold 380
    // 5 × 98 = 490 only fits because the old 380 hold is released in the same command
    PlaceOrderResponse r = orders.replace(p.principal(), o.orderId(), new ReplaceOrderRequest(cid(),
        new BigDecimal("5"), new BigDecimal("98.00"), null, null, null, null, null, null), meta());
    OrderDto fresh = r.order();
    assertThat(fresh.orderId()).isNotEqualTo(o.orderId());
    assertThat(fresh.replacesOrderId()).isEqualTo(o.orderId());
    assertThat(fresh.reservedCash()).isEqualByComparingTo("490.00");
    assertThat(fresh.qty()).isEqualByComparingTo("5");
    OrderDto old = order(p, o.orderId());
    assertThat(old.status()).isEqualTo(OrderStatus.REPLACED);
    assertThat(old.replacedByOrderId()).isEqualTo(fresh.orderId());
    assertThat(old.reservedCash()).isEqualByComparingTo("0");
    // a replaced order cannot be replaced again; a failing replacement changes nothing
    expect(ErrorCode.ORDER_NOT_REPLACEABLE, () -> orders.replace(p.principal(), o.orderId(),
        new ReplaceOrderRequest(cid(), null, new BigDecimal("97.00"), null, null, null, null, null, null), meta()));
    expect(ErrorCode.INSUFFICIENT_BUYING_POWER, () -> orders.replace(p.principal(), fresh.orderId(),
        new ReplaceOrderRequest(cid(), new BigDecimal("6"), null, null, null, null, null, null, null), meta()));
    assertThat(order(p, fresh.orderId()).status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void fractionalOrdersCannotBeReplaced() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, notional(cid(), s, Side.BUY, "50.00")).order();
    expect(ErrorCode.FRACTIONAL_NOT_REPLACEABLE, () -> orders.replace(p.principal(), o.orderId(),
        new ReplaceOrderRequest(cid(), new BigDecimal("1"), null, null, null, null, null, null, null), meta()));
  }

  @Test
  void closePositionSellsTheAvailableQuantity() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto buy = place(p, notional(cid(), s, Side.BUY, "150.00")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, "99.98", 300, "100.00", 300);
    step();
    BigDecimal held = order(p, buy.orderId()).filledQty();
    assertThat(held).isEqualByComparingTo("1.5");
    String cid = cid();
    PlaceOrderResponse close = orders.closePosition(p.principal(), s, cid, null, meta());
    assertThat(close.order().side()).isEqualTo(Side.SELL);
    assertThat(close.order().type()).isEqualTo(OrderType.MARKET);
    assertThat(close.order().fractional()).isTrue();
    assertThat(close.order().qty()).isEqualByComparingTo("1.5");
    assertThat(orders.closePosition(p.principal(), s, cid, null, meta()).replayed()).isTrue();
    expect(ErrorCode.INSUFFICIENT_POSITION, () -> orders.closePosition(p.principal(), s, cid(), null, meta()));
    time.advance(Duration.ofSeconds(1));
    quote(s, "101.00", 300, "101.02", 300);
    step();
    assertThat(order(p, close.order().orderId()).status()).isEqualTo(OrderStatus.FILLED);
    assertThat(accountQueries.positions(p.accountId())).isEmpty();
    expect(ErrorCode.NOT_FOUND, () -> orders.closePosition(p.principal(), s, cid(), null, meta()));
  }

  @Test
  void previewValidatesWithoutWritingAndExplainsTheSession() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    PreviewDto ok = orders.preview(p.principal(), market(null, s, Side.BUY, "2"));
    assertThat(ok.valid()).isTrue();
    assertThat(ok.estimate().price()).isEqualByComparingTo("100.00");
    assertThat(ok.estimate().hold()).isEqualByComparingTo("204.00");
    assertThat(ok.buyingPowerAfter()).isEqualByComparingTo("296.00");
    PreviewDto bad = orders.preview(p.principal(), market(null, s, Side.BUY, "5"));
    assertThat(bad.valid()).isFalse();
    assertThat(bad.errors()).singleElement().satisfies(e -> {
      assertThat(e.code()).isEqualTo("INSUFFICIENT_BUYING_POWER");
      assertThat(e.field()).isEqualTo("qty");
    });
    time.set(et("2026-10-09T18:00:00"));
    PreviewDto noRef = orders.preview(p.principal(), market(null, s, Side.BUY, "1"));
    assertThat(noRef.errors()).extracting(e -> e.code()).containsExactly("MARKET_DATA_UNAVAILABLE");
    assertThat(noRef.sessionNote()).isEqualTo("Order will queue for 09:30 ET");
    print(s, "100.00", 100);
    PreviewDto queued = orders.preview(p.principal(), market(null, s, Side.BUY, "1"));
    assertThat(queued.valid()).isTrue();
    assertThat(queued.estimate().hold()).isEqualByComparingTo("115.00");
    assertThat(queued.sessionNote()).isEqualTo("Order will queue for 09:30 ET");
    assertThat(queued.warnings()).extracting(w -> w.code()).contains("QUEUED_FOR_OPEN");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ?").param(p.accountId())
        .query(Integer.class).single()).isZero();
  }

  @Test
  void thePhaseOneMatrixIsEnforced() {
    Player p = human();
    Player bot = bot();
    String s = symbol();
    print(s, "100.00", 100);
    quote(s, "99.98", 300, "100.00", 300);
    expect(ErrorCode.SYMBOL_NOT_FOUND, () -> place(p, market(cid(), "ZZZZ9", Side.BUY, "1")));
    expect(ErrorCode.QTY_OR_NOTIONAL_REQUIRED, () -> place(p, req(cid(), s, OrderType.MARKET, null, null, null,
        TimeInForce.DAY, false)));
    expect(ErrorCode.INVALID_QTY, () -> place(p, market(cid(), s, Side.BUY, "0.000001")));
    expect(ErrorCode.INVALID_QTY, () -> place(p, market(cid(), s, Side.BUY, "200001")));
    expect(ErrorCode.FRACTIONAL_MARKET_ONLY, () -> place(p, limit(cid(), s, Side.BUY, "0.5", "99.00",
        TimeInForce.DAY, false)));
    expect(ErrorCode.FRACTIONAL_MARKET_ONLY, () -> place(p, req(cid(), s, OrderType.LIMIT, null, "50.00", "99.00",
        TimeInForce.DAY, false)));
    expect(ErrorCode.NOTIONAL_TOO_SMALL, () -> place(p, notional(cid(), s, Side.BUY, "0.99")));
    expect(ErrorCode.LIMIT_PRICE_REQUIRED, () -> place(p, req(cid(), s, OrderType.LIMIT, "1", null, null,
        TimeInForce.DAY, false)));
    expect(ErrorCode.PRICE_NOT_ALLOWED, () -> place(p, req(cid(), s, OrderType.MARKET, "1", null, "99.00",
        TimeInForce.DAY, false)));
    expect(ErrorCode.INVALID_TICK, () -> place(p, limit(cid(), s, Side.BUY, "1", "99.005", TimeInForce.DAY,
        false)));
    expect(ErrorCode.EXT_HOURS_LIMIT_ONLY, () -> place(p, req(cid(), s, OrderType.MARKET, "1", null, null,
        TimeInForce.DAY, true)));
    expect(ErrorCode.TIF_NOT_ALLOWED, () -> place(p, req(cid(), s, OrderType.MARKET, "1", null, null,
        TimeInForce.GTC, false)));
    expect(ErrorCode.PRICE_TOO_FAR, () -> place(p, limit(cid(), s, Side.BUY, "1", "40.00", TimeInForce.DAY,
        false)));
    expect(ErrorCode.RATIONALE_REQUIRED, () -> place(bot, new PlaceOrderRequest(cid(), s, Side.BUY, OrderType.MARKET,
        BigDecimal.ONE, null, null, null, null, null, TimeInForce.DAY, false, null, null, Set.of())));
    // STOP / STOP_LIMIT / TRAILING_STOP are out of this milestone's scope: rejected, never accepted and stranded
    expect(ErrorCode.VALIDATION_FAILED, () -> place(p, new PlaceOrderRequest(cid(), s, Side.BUY, OrderType.STOP,
        BigDecimal.ONE, null, null, new BigDecimal("101.00"), null, null, TimeInForce.DAY, false, "x", null,
        Set.of())));
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ?").param(p.accountId())
        .query(Integer.class).single()).as("synchronous rejections are not persisted (D15)").isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'ORDER_REJECT' AND actor_player_id = ?")
        .param(p.id()).query(Integer.class).single()).isGreaterThanOrEqualTo(14);
  }

  @Test
  void aNonFractionableSymbolTakesWholeSharesOnly() {
    Player p = human();
    String s = "W" + next();
    md.withAsset(new com.stocks.contract.marketdata.model.Asset(s, s + " Corp", "NYSE",
        com.stocks.common.domain.AssetType.COMMON_STOCK, true, false, true, true, false,
        com.stocks.common.domain.LiquidityTier.MID, true));
    quote(s, "9.99", 300, "10.00", 300);
    expect(ErrorCode.NOT_FRACTIONABLE, () -> place(p, notional(cid(), s, Side.BUY, "5.00")));
    expect(ErrorCode.NOT_FRACTIONABLE, () -> place(p, market(cid(), s, Side.BUY, "0.5")));
    assertThat(place(p, market(cid(), s, Side.BUY, "1")).order().status()).isEqualTo(OrderStatus.PENDING);
  }

  @Test
  void aFrozenPlayerAndAHaltedEngineCannotTrade() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    fx.status(p.ref(), "FROZEN");
    expect(ErrorCode.PLAYER_FROZEN, () -> place(p, market(cid(), s, Side.BUY, "1")));
    fx.status(p.ref(), "ACTIVE");
    engine.run(new EngineCommand.HaltEngineCmd("test", 0));
    try {
      expect(ErrorCode.ENGINE_HALTED, () -> place(p, market(cid(), s, Side.BUY, "1")));
      assertThat(orders.preview(p.principal(), market(null, s, Side.BUY, "1")).errors())
          .extracting(e -> e.code()).containsExactly("ENGINE_HALTED");
    } finally {
      engine.run(new EngineCommand.ResumeEngineCmd(0));
    }
    assertThat(place(p, market(cid(), s, Side.BUY, "1")).order().status()).isEqualTo(OrderStatus.PENDING);
  }

  private static PlaceOrderRequest req(String cid, String s, OrderType type, String qty, String notional,
                                       String limit, TimeInForce tif, boolean ext) {
    return new PlaceOrderRequest(cid, s, Side.BUY, type, qty == null ? null : new BigDecimal(qty),
        notional == null ? null : new BigDecimal(notional), limit == null ? null : new BigDecimal(limit), null, null,
        null, tif, ext, "test", null, Set.of());
  }

  private static void expect(ErrorCode code, Runnable call) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
        (Consumer<ApiException>) e -> assertThat(e.code()).isEqualTo(code));
  }
}
