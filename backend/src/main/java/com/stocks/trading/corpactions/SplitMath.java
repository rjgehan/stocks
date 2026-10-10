package com.stocks.trading.corpactions;

import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The split arithmetic of §14, pure. {@code oldRate → newRate} shares: a 2-for-1 forward split is old 1 / new 2, a
 * 1-for-8 reverse split is old 8 / new 1.
 * <ol>
 *   <li><b>{@code newRate / oldRate} is never computed</b> (it does not terminate for 3:2 or 1-for-3): every quantity
 *       is {@code SPLIT_QTY(q) = (q × newRate).divide(oldRate, 5, FLOOR)}, multiply first, divide last.</li>
 *   <li>Per open lot {@code qty' = SPLIT_QTY(qty_remaining)} and {@code qty_original' = SPLIT_QTY(qty_original)}
 *       (floored at 0.00001); cost is unchanged.</li>
 *   <li>{@code position.qty' = Σ lot.qty'} (never floored separately, so I5 holds by construction).</li>
 *   <li>The holder is owed {@code entitled = SPLIT_QTY(position.qty)}; {@code dust = entitled − Σ lot.qty'}.
 *       Fractionable: the dust is paid as cash in lieu, usually $0.00 (then it is forfeited). Non-fractionable: the
 *       position keeps {@code floor(entitled)} whole shares, the excess is removed from the newest lots first
 *       (cost relieved pro rata, HALF_EVEN to cents) and {@code entitled − whole} is paid as cash in lieu.</li>
 *   <li>A lot whose quantity reaches 0 is closed and its remaining cost is relieved as realized P&L.</li>
 * </ol>
 * {@code CASH_IN_LIEU(q) = FLOOR_cents((q × prevClose × oldRate).divide(newRate, 10, FLOOR))}: {@code prevClose} is
 * the raw (pre-split) price of one old share.
 */
public final class SplitMath {

  /** Scale of the intermediate cash-in-lieu division (§14). */
  static final int CIL_SCALE = 10;

  /** One open lot before the split, FIFO order (oldest first). */
  public record Lot(long id, BigDecimal qtyRemaining, BigDecimal qtyOriginal, BigDecimal cost) {}

  /** One lot after the split. {@code absorbedCashInLieu}: the lot that carries the cash-in-lieu ledger row. */
  public record LotResult(long id, BigDecimal qtyBefore, BigDecimal qtyAfter, BigDecimal qtyOriginalAfter,
                          BigDecimal costBefore, BigDecimal costAfter, boolean closed, boolean absorbedCashInLieu) {}

  /**
   * The effect on one position. {@code dust = entitled − Σ lot.qty'} before any whole-share rounding;
   * {@code cashInLieuQty} is the quantity paid in cash; {@code forfeitedQty} the quantity neither kept nor paid
   * (fractionable dust worth less than a cent).
   */
  public record Result(List<LotResult> lots, BigDecimal qtyBefore, BigDecimal qtyAfter, BigDecimal costBefore,
                       BigDecimal costAfter, BigDecimal entitled, BigDecimal dust, BigDecimal cashInLieuQty,
                       BigDecimal cashInLieu, BigDecimal forfeitedQty, BigDecimal relievedCost,
                       BigDecimal realizedPnl) {}

  private SplitMath() {}

  /** {@code SPLIT_QTY}: {@code (qty × newRate).divide(oldRate, 5, FLOOR)}. */
  public static BigDecimal splitQty(BigDecimal qty, BigDecimal oldRate, BigDecimal newRate) {
    return Rounding.splitQty(qty, oldRate, newRate);
  }

  /** {@code CASH_IN_LIEU} for {@code qty} new shares, priced from the raw close of one old share. */
  public static BigDecimal cashInLieu(BigDecimal qty, BigDecimal prevClose, BigDecimal oldRate, BigDecimal newRate) {
    if (qty.signum() == 0) {
      return Money.ZERO;
    }
    Objects.requireNonNull(prevClose, "a reference price is required for cash in lieu");
    BigDecimal exact = qty.multiply(prevClose).multiply(oldRate).divide(newRate, CIL_SCALE, RoundingMode.FLOOR);
    return Rounding.CASH_IN_LIEU.apply(exact);
  }

  /**
   * Applies a split to one position's open lots.
   *
   * @param lots          the position's open lots, oldest first (their sum is the position, I5)
   * @param fractionable  whether the symbol trades in fractions
   * @param prevCloseOrNull the raw previous close of one old share; may be null only when no cash in lieu is due
   * @throws IllegalArgumentException for non-positive or equal rates
   * @throws NullPointerException     when cash in lieu is due and there is no price
   */
  public static Result apply(List<Lot> lots, BigDecimal oldRate, BigDecimal newRate, boolean fractionable,
                             BigDecimal prevCloseOrNull) {
    requireRates(oldRate, newRate);
    BigDecimal qtyBefore = Qty.ZERO;
    BigDecimal costBefore = Money.ZERO;
    int n = lots.size();
    BigDecimal[] qty = new BigDecimal[n];
    BigDecimal[] cost = new BigDecimal[n];
    BigDecimal sumSplit = Qty.ZERO;
    for (int i = 0; i < n; i++) {
      Lot l = lots.get(i);
      qtyBefore = qtyBefore.add(l.qtyRemaining());
      costBefore = costBefore.add(l.cost());
      qty[i] = splitQty(l.qtyRemaining(), oldRate, newRate);
      cost[i] = l.cost();
      sumSplit = sumSplit.add(qty[i]);
    }
    BigDecimal entitled = splitQty(qtyBefore, oldRate, newRate);
    BigDecimal dust = entitled.subtract(sumSplit);

    BigDecimal relieved = Money.ZERO;
    BigDecimal cilQty;
    int absorbing = -1;
    if (fractionable) {
      cilQty = dust;
    } else {
      BigDecimal whole = entitled.setScale(0, RoundingMode.FLOOR);
      BigDecimal keep = whole.min(sumSplit);
      BigDecimal remove = sumSplit.subtract(keep);
      for (int i = n - 1; i >= 0 && remove.signum() > 0; i--) {
        if (qty[i].signum() == 0) {
          continue;
        }
        BigDecimal take = remove.min(qty[i]);
        BigDecimal newQty = qty[i].subtract(take);
        BigDecimal newCost = newQty.signum() == 0 ? Money.ZERO
            : cost[i].multiply(newQty).divide(qty[i], 2, RoundingMode.HALF_EVEN);
        relieved = relieved.add(cost[i].subtract(newCost));
        if (absorbing < 0) {
          absorbing = i;
        }
        qty[i] = newQty;
        cost[i] = newCost;
        remove = remove.subtract(take);
      }
      cilQty = entitled.subtract(keep);
    }
    // lots emptied by the split itself (a sliver under a reverse split): their cost is realized
    BigDecimal emptied = Money.ZERO;
    for (int i = 0; i < n; i++) {
      if (qty[i].signum() == 0 && cost[i].signum() > 0) {
        emptied = emptied.add(cost[i]);
        cost[i] = Money.ZERO;
      }
    }
    BigDecimal cil = cashInLieu(cilQty, prevCloseOrNull, oldRate, newRate);
    BigDecimal forfeited = cil.signum() == 0 ? cilQty : Qty.ZERO;
    if (cil.signum() > 0 && absorbing < 0) {
      absorbing = newestWithShares(qty, n);
    }

    List<LotResult> out = new ArrayList<>(n);
    BigDecimal qtyAfter = Qty.ZERO;
    BigDecimal costAfter = Money.ZERO;
    for (int i = 0; i < n; i++) {
      Lot l = lots.get(i);
      BigDecimal original = splitQty(l.qtyOriginal(), oldRate, newRate).max(Qty.MIN_FRACTION);
      original = original.max(qty[i]);
      out.add(new LotResult(l.id(), l.qtyRemaining(), Qty.normalize(qty[i]), Qty.normalize(original),
          Money.cents(l.cost()), Money.cents(cost[i]), qty[i].signum() == 0, cil.signum() > 0 && i == absorbing));
      qtyAfter = qtyAfter.add(qty[i]);
      costAfter = costAfter.add(cost[i]);
    }
    BigDecimal totalRelieved = relieved.add(emptied);
    return new Result(List.copyOf(out), Qty.normalize(qtyBefore), Qty.normalize(qtyAfter),
        Money.cents(costBefore), Money.cents(costAfter), Qty.normalize(entitled), Qty.normalize(dust),
        Qty.normalize(cilQty), cil, Qty.normalize(forfeited), Money.cents(totalRelieved),
        Money.cents(cil.subtract(totalRelieved)));
  }

  /**
   * A split applied <b>as of now</b> to a position that traded after the ex cut-off (§14, the admin's APPLY of a
   * split found late): only the shares held at the cut-off are split. The holder is owed
   * {@code entitled = SPLIT_QTY(qtyAtCutoff)}; the position keeps {@code target = entitled} (non-fractionable: its
   * whole part, the fraction is paid as cash in lieu) and so changes by {@code Δ = target − qtyAtCutoff}. Shares bought
   * after the cut-off are not changed.
   * <ul>
   *   <li>{@code Δ > 0} (forward): the shares go onto the oldest open lot, cost unchanged; without an open lot (the
   *       position was sold since) they are paid as cash in lieu.</li>
   *   <li>{@code Δ < 0} (reverse): the shares are removed from the oldest lots first, keeping their cost, except that
   *       a lot emptied this way is closed and its remaining cost is realized.</li>
   * </ul>
   *
   * @param lots         the position's open lots now, oldest first (may be empty)
   * @param qtyAtCutoff  the quantity held at the ex cut-off (&gt; 0)
   * @throws IllegalStateException when a reverse split must remove more shares than the position holds now
   * @throws NullPointerException  when cash in lieu is due and there is no price
   */
  public static Result applyDelta(List<Lot> lots, BigDecimal qtyAtCutoff, BigDecimal oldRate, BigDecimal newRate,
                                  boolean fractionable, BigDecimal prevCloseOrNull) {
    requireRates(oldRate, newRate);
    int n = lots.size();
    BigDecimal[] qty = new BigDecimal[n];
    BigDecimal[] cost = new BigDecimal[n];
    BigDecimal qtyBefore = Qty.ZERO;
    BigDecimal costBefore = Money.ZERO;
    for (int i = 0; i < n; i++) {
      qty[i] = lots.get(i).qtyRemaining();
      cost[i] = lots.get(i).cost();
      qtyBefore = qtyBefore.add(qty[i]);
      costBefore = costBefore.add(cost[i]);
    }
    BigDecimal entitled = splitQty(qtyAtCutoff, oldRate, newRate);
    BigDecimal target = fractionable ? entitled : entitled.setScale(0, RoundingMode.FLOOR);
    BigDecimal cilQty = entitled.subtract(target);
    BigDecimal delta = target.subtract(qtyAtCutoff);
    BigDecimal emptied = Money.ZERO;
    int absorbing = -1;
    if (delta.signum() > 0) {
      if (n == 0) {
        cilQty = cilQty.add(delta);                // sold since the cut-off: the extra shares are paid in cash
      } else {
        qty[0] = qty[0].add(delta);
        absorbing = 0;
      }
    } else if (delta.signum() < 0) {
      BigDecimal remove = delta.negate();
      if (remove.compareTo(qtyBefore) > 0) {
        throw new IllegalStateException("the split takes away " + remove.stripTrailingZeros().toPlainString()
            + " shares, but only " + qtyBefore.stripTrailingZeros().toPlainString() + " are held now");
      }
      for (int i = 0; i < n && remove.signum() > 0; i++) {
        BigDecimal take = remove.min(qty[i]);
        qty[i] = qty[i].subtract(take);
        remove = remove.subtract(take);
        if (qty[i].signum() == 0) {
          emptied = emptied.add(cost[i]);
          cost[i] = Money.ZERO;
        }
      }
    }
    BigDecimal cil = cashInLieu(cilQty, prevCloseOrNull, oldRate, newRate);
    BigDecimal forfeited = cil.signum() == 0 ? cilQty : Qty.ZERO;
    if (cil.signum() > 0 && absorbing < 0 && n > 0) {
      absorbing = newestWithShares(qty, n);
    }
    List<LotResult> out = new ArrayList<>(n);
    BigDecimal qtyAfter = Qty.ZERO;
    BigDecimal costAfter = Money.ZERO;
    for (int i = 0; i < n; i++) {
      Lot l = lots.get(i);
      BigDecimal gained = qty[i].subtract(l.qtyRemaining()).max(Qty.ZERO);
      BigDecimal original = l.qtyOriginal().add(gained).max(qty[i]);
      out.add(new LotResult(l.id(), l.qtyRemaining(), Qty.normalize(qty[i]), Qty.normalize(original),
          Money.cents(l.cost()), Money.cents(cost[i]), qty[i].signum() == 0, cil.signum() > 0 && i == absorbing));
      qtyAfter = qtyAfter.add(qty[i]);
      costAfter = costAfter.add(cost[i]);
    }
    return new Result(List.copyOf(out), Qty.normalize(qtyBefore), Qty.normalize(qtyAfter), Money.cents(costBefore),
        Money.cents(costAfter), Qty.normalize(entitled), Qty.normalize(Qty.ZERO), Qty.normalize(cilQty), cil,
        Qty.normalize(forfeited), Money.cents(emptied), Money.cents(cil.subtract(emptied)));
  }

  private static int newestWithShares(BigDecimal[] qty, int n) {
    for (int i = n - 1; i >= 0; i--) {
      if (qty[i].signum() > 0) {
        return i;
      }
    }
    return n - 1;
  }

  static void requireRates(BigDecimal oldRate, BigDecimal newRate) {
    if (oldRate == null || newRate == null || oldRate.signum() <= 0 || newRate.signum() <= 0) {
      throw new IllegalArgumentException("Split rates must be positive (old " + oldRate + ", new " + newRate + ")");
    }
    if (oldRate.compareTo(newRate) == 0) {
      throw new IllegalArgumentException("Split rates must differ (" + oldRate + ")");
    }
  }
}
