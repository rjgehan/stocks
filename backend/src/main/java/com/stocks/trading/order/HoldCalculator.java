package com.stocks.trading.order;

import com.stocks.common.domain.OptionSymbol;
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
 *   <tr><td>LIMIT, STOP_LIMIT</td><td>{@code qty × limit + estFees}</td></tr>
 *   <tr><td>STOP</td><td>{@code qty × max(stop, ref) × 1.02 + estFees}</td></tr>
 *   <tr><td>TRAILING_STOP</td><td>{@code qty × (ref + trail) × 1.02 + estFees}, trail = the amount or
 *       {@code ref × pct / 100}</td></tr>
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
    if (OrderTypes.usesLimit(o.type())) {
      return o.limitPrice();
    }
    BigDecimal reference = ref != null && ref.signum() > 0 ? ref : null;
    if (o.type() == OrderType.STOP) {
      // §10.3: the stop triggers into a market order, so hold the stop (or the market, if higher) plus the cushion
      BigDecimal base = reference == null || o.stopPrice().compareTo(reference) >= 0 ? o.stopPrice() : reference;
      return cushioned(base, rules.holdPctRegular());
    }
    if (o.type() == OrderType.TRAILING_STOP) {
      if (reference == null) {
        return null;
      }
      return cushioned(reference.add(trail(o, reference)), rules.holdPctRegular());
    }
    if (!queued && ask != null && ask.signum() > 0) {
      return ask.multiply(rules.holdPctRegular()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }
    if (ref != null && ref.signum() > 0) {
      return ref.multiply(rules.holdPctQueued()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
    }
    return null;
  }

  /** A trailing stop's distance in dollars: the amount, or {@code ref × pct / 100} (§10.3, §12.7). */
  public static BigDecimal trail(NormalizedOrder o, BigDecimal ref) {
    if (o.trailAmount() != null) {
      return o.trailAmount();
    }
    return ref.multiply(o.trailPercent()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
  }

  private static BigDecimal cushioned(BigDecimal price, BigDecimal pct) {
    return price.multiply(pct).divide(HUNDRED, 8, RoundingMode.HALF_UP);
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
    return holdFor(o.symbol(), o.qty(), price, tradeDate);
  }

  /** {@code HOLD(qty × price + estFees(qty))} for a stock. */
  public BigDecimal holdFor(BigDecimal qty, BigDecimal price, LocalDate tradeDate) {
    return holdFor(null, qty, price, tradeDate);
  }

  /**
   * {@code HOLD(qty × price + estFees(qty))}; a crypto pair has no fees (§29); an option holds
   * {@code qty × price × 100} (§30).
   */
  public BigDecimal holdFor(String symbol, BigDecimal qty, BigDecimal price, LocalDate tradeDate) {
    BigDecimal unit = OptionSymbol.unitValue(symbol, price);
    BigDecimal principal = Rounding.PRINCIPAL_BUY.multiply(qty, unit);
    BigDecimal est = fees.estimate(symbol, Side.BUY, qty, principal, tradeDate).total();
    return Rounding.HOLD.apply(qty.multiply(unit).add(est));
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
