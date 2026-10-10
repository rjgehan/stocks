package com.stocks.trading.order;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.fees.FeeCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Buying-power holds (§10.3), all {@link Rounding#HOLD} (CEILING to cents):
 * <table>
 *   <caption>reserved_cash of a BUY</caption>
 *   <tr><td>MARKET by shares, entered in REGULAR</td><td>{@code qty × ask × 1.02 + estFees}</td></tr>
 *   <tr><td>MARKET by shares, queued (or no ask)</td><td>{@code qty × ref × 1.15 + estFees}</td></tr>
 *   <tr><td>MARKET notional</td><td>{@code notional} (all-in, D7)</td></tr>
 *   <tr><td>LIMIT</td><td>{@code qty × limit + estFees}</td></tr>
 * </table>
 * A SELL reserves shares instead: {@code reserved_qty = qty}, or for a notional sell
 * {@code min(available, ceil5(notional / ref × 1.05))}. The cushions come from Webull [V].
 */
@Component
public class HoldCalculator {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal NOTIONAL_SELL_CUSHION = new BigDecimal("1.05");

  private final FeeCalculator fees;
  private final RulesProperties rules;

  public HoldCalculator(FeeCalculator fees, RulesProperties rules) {
    this.fees = fees;
    this.rules = rules;
  }

  /** The per-share price a BUY hold is based on, or null when no price is known (check 25). */
  public BigDecimal holdPrice(NormalizedOrder o, boolean queued, BigDecimal ask, BigDecimal ref) {
    if (o.type() == OrderType.LIMIT) {
      return o.limitPrice();
    }
    if (!queued && ask != null && ask.signum() > 0) {
      return ask.multiply(rules.holdPctRegular()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }
    if (ref != null && ref.signum() > 0) {
      return ref.multiply(rules.holdPctQueued()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }
    return null;
  }

  /**
   * {@code reserved_cash} of a BUY, or null when a market-by-shares order has no price to hold against
   * ({@code MARKET_DATA_UNAVAILABLE}).
   */
  public BigDecimal buyHold(NormalizedOrder o, boolean queued, BigDecimal ask, BigDecimal ref, LocalDate tradeDate) {
    if (o.notional() != null) {
      return Money.require("notional", o.notional());
    }
    BigDecimal price = holdPrice(o, queued, ask, ref);
    if (price == null) {
      return null;
    }
    return holdFor(o.qty(), price, tradeDate);
  }

  /** {@code HOLD(qty × price + estFees(qty))}. */
  public BigDecimal holdFor(BigDecimal qty, BigDecimal price, LocalDate tradeDate) {
    BigDecimal principal = Rounding.PRINCIPAL.multiply(qty, price);
    BigDecimal est = fees.estimate(Side.BUY, qty, principal, tradeDate).total();
    return Rounding.HOLD.apply(qty.multiply(price).add(est));
  }

  /** {@code reserved_qty} of a SELL. */
  public BigDecimal sellReservedQty(NormalizedOrder o, BigDecimal available, BigDecimal ref) {
    if (o.notional() == null) {
      return Qty.normalize(o.qty());
    }
    if (ref == null || ref.signum() <= 0) {
      return Qty.normalize(available);
    }
    BigDecimal est = o.notional().multiply(NOTIONAL_SELL_CUSHION).divide(ref, Qty.SCALE, RoundingMode.CEILING);
    return Qty.normalize(est.min(available));
  }
}
