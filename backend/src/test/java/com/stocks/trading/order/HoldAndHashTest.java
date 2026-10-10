package com.stocks.trading.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.testkit.FeeRows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §10.3 holds and §11.7 request hashing. */
class HoldAndHashTest {

  static final LocalDate DAY = LocalDate.of(2026, 10, 9);
  final HoldCalculator holds = new HoldCalculator(FeeRows.calculator(),
      RulesProperties.defaults());

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  static NormalizedOrder order(OrderType type, Side side, String qty, String notional, String limit) {
    return NormalizedOrder.of(new PlaceOrderRequest("c1", "aapl", side, type, qty == null ? null : d(qty),
        notional == null ? null : d(notional), limit == null ? null : d(limit), null, null, null, null, null, null,
        null, null));
  }

  @Test
  void marketBuyInRegularHoursHolds102PercentOfTheAsk() {
    BigDecimal hold = holds.buyHold(order(OrderType.MARKET, Side.BUY, "2", null, null), false, d("227.42"),
        d("227.00"), DAY);
    // 2 × 227.42 × 1.02 = 463.9368 → CEILING 463.94
    assertThat(hold).isEqualByComparingTo("463.94");
  }

  @Test
  void queuedMarketBuyHolds115PercentOfTheReference() {
    BigDecimal hold = holds.buyHold(order(OrderType.MARKET, Side.BUY, "2", null, null), true, d("227.42"),
        d("227.00"), DAY);
    // 2 × 227.00 × 1.15 = 522.10
    assertThat(hold).isEqualByComparingTo("522.10");
    assertThat(holds.buyHold(order(OrderType.MARKET, Side.BUY, "2", null, null), true, null, null, DAY)).isNull();
  }

  @Test
  void limitAndNotionalHolds() {
    assertThat(holds.buyHold(order(OrderType.LIMIT, Side.BUY, "3", null, "10.01"), false, null, null, DAY))
        .isEqualByComparingTo("30.03");
    assertThat(holds.buyHold(order(OrderType.MARKET, Side.BUY, null, "125.00", null), false, null, null, DAY))
        .isEqualByComparingTo("125.00");
    // 4,000 shares at $1.00 limit: 4000 + CAT 0.01
    assertThat(holds.holdFor(d("4000"), d("1.00"), DAY)).isEqualByComparingTo("4000.01");
  }

  static NormalizedOrder stopOrder(OrderType type, Side side, String qty, String limit, String stop, String amount,
                                   String pct) {
    return NormalizedOrder.of(new PlaceOrderRequest("c1", "aapl", side, type, d(qty), null,
        limit == null ? null : d(limit), stop == null ? null : d(stop), amount == null ? null : d(amount),
        pct == null ? null : d(pct), null, null, null, null, null));
  }

  @Test
  void stopBuysHold102PercentOfTheHigherOfStopAndReference() {
    NormalizedOrder stop = stopOrder(OrderType.STOP, Side.BUY, "2", null, "105.00", null, null);
    // 2 × max(105, 100) × 1.02 = 214.20
    assertThat(holds.buyHold(stop, false, d("100.00"), d("100.00"), DAY)).isEqualByComparingTo("214.20");
    // the market already above the stop: 2 × 110 × 1.02 = 224.40 (queued or not)
    assertThat(holds.buyHold(stop, true, null, d("110.00"), DAY)).isEqualByComparingTo("224.40");
    // no reference: the stop itself
    assertThat(holds.buyHold(stop, false, null, null, DAY)).isEqualByComparingTo("214.20");
  }

  @Test
  void stopLimitBuysHoldTheLimitAndTrailingBuysTheReferencePlusTheTrail() {
    assertThat(holds.buyHold(stopOrder(OrderType.STOP_LIMIT, Side.BUY, "3", "52.50", "52.00", null, null), false,
        d("50.00"), d("50.00"), DAY)).isEqualByComparingTo("157.50");
    // 4 × (50 + 2) × 1.02 = 212.16
    assertThat(holds.buyHold(stopOrder(OrderType.TRAILING_STOP, Side.BUY, "4", null, null, "2.00", null), false,
        d("50.02"), d("50.00"), DAY)).isEqualByComparingTo("212.16");
    // 4 × (50 + 50 × 5%) × 1.02 = 214.20
    assertThat(holds.buyHold(stopOrder(OrderType.TRAILING_STOP, Side.BUY, "4", null, null, null, "5"), false,
        null, d("50.00"), DAY)).isEqualByComparingTo("214.20");
    assertThat(holds.buyHold(stopOrder(OrderType.TRAILING_STOP, Side.BUY, "4", null, null, null, "5"), false,
        d("50.02"), null, DAY)).as("no reference: MARKET_DATA_UNAVAILABLE").isNull();
    assertThat(HoldCalculator.trail(stopOrder(OrderType.TRAILING_STOP, Side.SELL, "1", null, null, null, "2.5"),
        d("80.00"))).isEqualByComparingTo("2.00");
  }

  @Test
  void stopAndTrailFieldsArePartOfTheRequestHash() {
    NormalizedOrder a = stopOrder(OrderType.STOP, Side.SELL, "2", null, "95.00", null, null);
    assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(stopOrder(OrderType.STOP, Side.SELL, "2", null,
        "95.0", null, null)));
    assertThat(RequestHasher.hash(a)).isNotEqualTo(RequestHasher.hash(stopOrder(OrderType.STOP, Side.SELL, "2", null,
        "95.01", null, null)));
    NormalizedOrder pct = stopOrder(OrderType.TRAILING_STOP, Side.SELL, "2", null, null, null, "5");
    assertThat(RequestHasher.hash(pct)).isEqualTo(RequestHasher.hash(stopOrder(OrderType.TRAILING_STOP, Side.SELL,
        "2", null, null, null, "5.000")));
    assertThat(RequestHasher.hash(pct)).isNotEqualTo(RequestHasher.hash(stopOrder(OrderType.TRAILING_STOP,
        Side.SELL, "2", null, null, "5", null)));
  }

  @Test
  void sellsReserveShares() {
    assertThat(holds.sellReservedQty(order(OrderType.MARKET, Side.SELL, "1.5", null, null), d("3"), d("10")))
        .isEqualByComparingTo("1.5");
    // notional 100 at ref 10 → 10.5 shares, capped by the 8 available
    assertThat(holds.sellReservedQty(order(OrderType.MARKET, Side.SELL, null, "100.00", null), d("8"), d("10")))
        .isEqualByComparingTo("8");
    assertThat(holds.sellReservedQty(order(OrderType.MARKET, Side.SELL, null, "50.00", null), d("8"), d("10")))
        .isEqualByComparingTo("5.25");
    assertThat(holds.sellReservedQty(order(OrderType.MARKET, Side.SELL, null, "50.00", null), d("8"), null))
        .isEqualByComparingTo("8");
  }

  @Test
  void hashIgnoresRationaleAckAndTrailingZerosButNotBusinessFields() {
    NormalizedOrder a = order(OrderType.LIMIT, Side.BUY, "2", null, "227.40");
    NormalizedOrder b = NormalizedOrder.of(new PlaceOrderRequest("c1", " AAPL ", Side.BUY, OrderType.LIMIT,
        d("2.000"), null, d("227.4"), null, null, null, TimeInForce.DAY, false, "different reason", true,
        Set.of("gfv_risk")));
    assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(b)).hasSize(64);
    assertThat(b.acknowledges(NormalizedOrder.ACK_GFV_RISK)).isTrue();
    NormalizedOrder c = order(OrderType.LIMIT, Side.BUY, "3", null, "227.40");
    assertThat(RequestHasher.hash(c)).isNotEqualTo(RequestHasher.hash(a));
    NormalizedOrder gtc = NormalizedOrder.of(new PlaceOrderRequest("c1", "AAPL", Side.BUY, OrderType.LIMIT, d("2"),
        null, d("227.40"), null, null, null, TimeInForce.GTC, null, null, null, null));
    assertThat(RequestHasher.hash(gtc)).isNotEqualTo(RequestHasher.hash(a));
  }

  @Test
  void canonicalJsonEscapesStrings() {
    assertThat(RequestHasher.canonicalJson(new java.util.TreeMap<>(Map.of("a", "x\"y\\z\u0001", "b", true))))
        .isEqualTo("{\"a\":\"x\\\"y\\\\z\\u0001\",\"b\":true}");
  }

  @Test
  void normalizationAppliesTheDefaults() {
    NormalizedOrder o = NormalizedOrder.of(new PlaceOrderRequest(" c-1 ", "nvda", Side.BUY, OrderType.MARKET, null,
        d("125.00"), null, null, null, null, null, null, "  why  ", null, null));
    assertThat(o.clientOrderId()).isEqualTo("c-1");
    assertThat(o.symbol()).isEqualTo("NVDA");
    assertThat(o.timeInForce()).isEqualTo(TimeInForce.DAY);
    assertThat(o.extendedHours()).isFalse();
    assertThat(o.rationale()).isEqualTo("why");
    assertThat(o.fractional()).isTrue();
    assertThat(o.isBuy()).isTrue();
    assertThat(o.withClientOrderId("x").clientOrderId()).isEqualTo("x");
    assertThat(o.withQty(d("1")).qty()).isEqualByComparingTo("1");
    assertThat(order(OrderType.MARKET, Side.BUY, "2", null, null).fractional()).isFalse();
    assertThat(order(OrderType.MARKET, Side.BUY, "0.5", null, null).fractional()).isTrue();
  }
}
