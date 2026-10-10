package com.stocks.trading.engine;

import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.Print;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Price-taker fill simulation (§12.4, §12.5), pure. Players never trade with each other and liquidity is not shared
 * between them (D9).
 *
 * <p><b>Marketable</b> (arrival, reopen, market orders): {@code touch = BUY ? ask : bid}; the cap is the limit price or
 * the market collar ({@code TICK_UP(ask × 1.05)} / {@code TICK_DOWN(bid × 0.95)}, set once). Fractional and notional
 * orders take ONE fill at the touch for the whole remaining quantity if the touch is within the cap. Whole-share
 * orders walk the book: level 0 fills the displayed size at the touch ({@code TOUCH}), levels 1–9 step by
 * {@code max(tick, ½ spread)} and fill {@code max(shown, 100)} each ({@code WALK}), never beyond the cap. A limit's
 * remainder rests; a market remainder waits {@code BEYOND_COLLAR}.
 *
 * <p><b>Resting limit</b>: a quote cross ({@code ask ≤ L} for a buy) fills {@code min(remaining, max(size, 100))} at
 * {@code L} ({@code RESTING}); otherwise prints strictly through {@code L} fill {@code min(remaining, Σ size)} at
 * {@code L} ({@code PRINT}). Prints exactly at {@code L} are ignored (queue position unknown).
 */
public final class FillModel {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal DEFAULT_SIZE = new BigDecimal("100");
  private static final BigDecimal HALF = new BigDecimal("0.5");
  private static final int MAX_LEVELS = 10;

  /** One execution to post. */
  public record Leg(BigDecimal qty, BigDecimal price, Liquidity liquidity) {}

  /**
   * The order as the model sees it. {@code remainingQty}: for a notional order, the quantity the evaluator sized
   * from the touch; {@code fractional} true for notional and fractional-quantity orders.
   */
  public record OrderView(Side side, OrderType type, boolean fractional, BigDecimal remainingQty,
                          BigDecimal limitPrice, BigDecimal collar) {}

  /**
   * @param collar       the collar after this evaluation (unchanged, or newly set for a market order)
   * @param rest         the limit order now rests ({@code has_rested = true})
   * @param beyondCollar a market order still has quantity that is beyond its collar
   */
  public record Decision(List<Leg> legs, BigDecimal collar, boolean rest, boolean beyondCollar) {
    public BigDecimal filledQty() {
      return legs.stream().map(Leg::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  private FillModel() {}

  /** The market collar of §12.4, from the first executable quote. */
  public static BigDecimal collar(Side side, ExecQuote q, BigDecimal collarPct) {
    BigDecimal pct = collarPct.divide(HUNDRED, 8, RoundingMode.HALF_UP);
    return side == Side.BUY
        ? Rounding.TICK_UP.apply(q.ask().multiply(BigDecimal.ONE.add(pct)))
        : Rounding.TICK_DOWN.apply(q.bid().multiply(BigDecimal.ONE.subtract(pct)));
  }

  /** Marketable execution against {@code q} (§12.4). {@code opening}: first fill of an order queued for the open. */
  public static Decision marketable(OrderView o, ExecQuote q, boolean opening, BigDecimal collarPct) {
    boolean buy = o.side() == Side.BUY;
    BigDecimal collar = o.collar();
    BigDecimal cap;
    if (o.type() == OrderType.LIMIT) {
      cap = o.limitPrice();
    } else {
      if (collar == null) {
        collar = collar(o.side(), q, collarPct);
      }
      cap = collar;
    }
    BigDecimal touch = buy ? q.ask() : q.bid();
    List<Leg> legs = new ArrayList<>();
    BigDecimal remaining = o.remainingQty();
    if (touch == null || touch.signum() <= 0 || remaining == null || remaining.signum() <= 0) {
      return new Decision(legs, collar, false, false);
    }
    Liquidity first = opening ? Liquidity.OPEN : Liquidity.TOUCH;
    if (o.fractional()) {
      if (within(buy, touch, cap)) {
        legs.add(new Leg(Qty.normalize(remaining), Prices.normalize(touch), first));
        remaining = BigDecimal.ZERO;
      }
    } else {
      BigDecimal shown = BigDecimal.valueOf(buy ? q.askSize() : q.bidSize());
      if (shown.signum() <= 0) {
        shown = DEFAULT_SIZE;
      }
      BigDecimal step = step(touch, q.spread());
      for (int k = 0; k < MAX_LEVELS && remaining.signum() > 0; k++) {
        BigDecimal offset = step.multiply(BigDecimal.valueOf(k));
        BigDecimal px = buy ? Rounding.TICK_UP.apply(touch.add(offset)) : Rounding.TICK_DOWN.apply(touch.subtract(offset));
        if (px.signum() <= 0 || !within(buy, px, cap)) {
          break;
        }
        BigDecimal size = k == 0 ? shown : shown.max(DEFAULT_SIZE);
        BigDecimal qk = remaining.min(size).setScale(0, RoundingMode.FLOOR);
        if (qk.signum() <= 0) {
          break;
        }
        legs.add(new Leg(Qty.normalize(qk), px, k == 0 ? first : Liquidity.WALK));
        remaining = remaining.subtract(qk);
      }
    }
    boolean left = remaining.signum() > 0;
    return new Decision(legs, collar, left && o.type() == OrderType.LIMIT,
        left && o.type() == OrderType.MARKET);
  }

  /**
   * A resting limit (§12.5): a quote cross first ({@code q} may be null in the IEX-dark windows), otherwise the
   * prints strictly through the limit.
   */
  public static Decision resting(OrderView o, ExecQuote q, List<Print> prints, boolean restingRequiresPrint) {
    boolean buy = o.side() == Side.BUY;
    BigDecimal limit = o.limitPrice();
    BigDecimal remaining = o.remainingQty();
    List<Leg> legs = new ArrayList<>();
    if (remaining == null || remaining.signum() <= 0) {
      return new Decision(legs, o.collar(), false, false);
    }
    if (q != null && !restingRequiresPrint) {
      BigDecimal touch = buy ? q.ask() : q.bid();
      long size = buy ? q.askSize() : q.bidSize();
      if (touch != null && touch.signum() > 0 && within(buy, touch, limit)) {
        BigDecimal qty = remaining.min(BigDecimal.valueOf(size).max(DEFAULT_SIZE));
        legs.add(new Leg(Qty.normalize(whole(o, qty)), Prices.normalize(limit), Liquidity.RESTING));
        return new Decision(nonZero(legs), o.collar(), false, false);
      }
    }
    BigDecimal through = BigDecimal.ZERO;
    for (Print p : prints) {
      boolean strictlyThrough = buy ? p.price().compareTo(limit) < 0 : p.price().compareTo(limit) > 0;
      if (strictlyThrough) {
        through = through.add(BigDecimal.valueOf(p.size()));
      }
    }
    if (through.signum() > 0) {
      BigDecimal qty = remaining.min(through);
      legs.add(new Leg(Qty.normalize(whole(o, qty)), Prices.normalize(limit), Liquidity.PRINT));
    }
    return new Decision(nonZero(legs), o.collar(), false, false);
  }

  private static BigDecimal whole(OrderView o, BigDecimal qty) {
    return o.fractional() ? qty : qty.setScale(0, RoundingMode.FLOOR);
  }

  private static List<Leg> nonZero(List<Leg> legs) {
    return legs.stream().filter(l -> l.qty().signum() > 0).toList();
  }

  /** {@code max(tick(touch), ½ spread rounded up to the touch's tick)}. */
  static BigDecimal step(BigDecimal touch, BigDecimal spread) {
    BigDecimal tick = Prices.tick(touch);
    BigDecimal half = spread == null || spread.signum() <= 0 ? BigDecimal.ZERO : spread.multiply(HALF);
    BigDecimal halfOnTick = half.divide(tick, 0, RoundingMode.CEILING).multiply(tick);
    return tick.max(halfOnTick);
  }

  private static boolean within(boolean buy, BigDecimal px, BigDecimal cap) {
    return buy ? px.compareTo(cap) <= 0 : px.compareTo(cap) >= 0;
  }
}
