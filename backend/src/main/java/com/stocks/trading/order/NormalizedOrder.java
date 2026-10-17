package com.stocks.trading.order;

import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.web.Text;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * A {@link PlaceOrderRequest} with its defaults applied (§18.3: {@code timeInForce} DAY, {@code extendedHours} false),
 * the symbol upper-cased and free text trimmed. Decimals keep the client's value: their scale and tick are checked by
 * {@link OrderValidator} (checks 10, 14, 16), never silently rounded.
 */
public record NormalizedOrder(String clientOrderId, String symbol, Side side, OrderType type, BigDecimal qty,
                              BigDecimal notional, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailAmount,
                              BigDecimal trailPercent, TimeInForce timeInForce, boolean extendedHours,
                              String rationale, boolean shareNote, Set<String> acknowledge, boolean doNotReduce) {

  public static final String ACK_GFV_RISK = "GFV_RISK";

  /** An order without the FINRA 5330 "do not reduce" flag (§14). */
  public NormalizedOrder(String clientOrderId, String symbol, Side side, OrderType type, BigDecimal qty,
                         BigDecimal notional, BigDecimal limitPrice, BigDecimal stopPrice, BigDecimal trailAmount,
                         BigDecimal trailPercent, TimeInForce timeInForce, boolean extendedHours, String rationale,
                         boolean shareNote, Set<String> acknowledge) {
    this(clientOrderId, symbol, side, type, qty, notional, limitPrice, stopPrice, trailAmount, trailPercent,
        timeInForce, extendedHours, rationale, shareNote, acknowledge, false);
  }

  public NormalizedOrder {
    acknowledge = acknowledge == null ? Set.of() : Set.copyOf(acknowledge);
  }

  public static NormalizedOrder of(PlaceOrderRequest r) {
    return new NormalizedOrder(Text.trimToNull(r.clientOrderId()), normalizeSymbol(r.symbol()), r.side(), r.type(),
        r.qty(), r.notional(), r.limitPrice(), r.stopPrice(), r.trailAmount(), r.trailPercent(),
        r.timeInForce() == null ? TimeInForce.DAY : r.timeInForce(), r.extendedHoursOrDefault(),
        Text.trimToNull(r.rationale()), Boolean.TRUE.equals(r.shareNote()),
        r.acknowledge() == null ? Set.of() : upper(r.acknowledge()), Boolean.TRUE.equals(r.doNotReduce()));
  }

  /** Trimmed and upper-cased; a crypto pair in its path spelling ({@code BTC-USD}) becomes {@code BTC/USD} (§29). */
  public static String normalizeSymbol(String symbol) {
    return symbol == null ? null : Crypto.fromPath(symbol.trim().toUpperCase(Locale.ROOT));
  }

  private static Set<String> upper(Set<String> values) {
    return values.stream().filter(v -> v != null).map(v -> v.trim().toUpperCase(Locale.ROOT))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /**
   * A notional order, or a quantity with a fractional part (D7). A crypto pair's quantity is fractional by nature
   * (0.5 BTC) and takes every order type, time in force and replace like a whole quantity (§29), so for crypto only a
   * notional order counts.
   */
  public boolean fractional() {
    return notional != null
        || (qty != null && qty.stripTrailingZeros().scale() > 0 && !Crypto.isCrypto(symbol));
  }

  public boolean isBuy() {
    return side == Side.BUY;
  }

  public boolean acknowledges(String code) {
    return acknowledge.contains(code);
  }

  public NormalizedOrder withClientOrderId(String id) {
    return new NormalizedOrder(id, symbol, side, type, qty, notional, limitPrice, stopPrice, trailAmount,
        trailPercent, timeInForce, extendedHours, rationale, shareNote, acknowledge, doNotReduce);
  }

  public NormalizedOrder withQty(BigDecimal newQty) {
    return new NormalizedOrder(clientOrderId, symbol, side, type, newQty, notional, limitPrice, stopPrice,
        trailAmount, trailPercent, timeInForce, extendedHours, rationale, shareNote, acknowledge, doNotReduce);
  }
}
