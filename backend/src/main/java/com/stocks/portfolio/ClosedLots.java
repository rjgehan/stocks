package com.stocks.portfolio;

import com.stocks.common.domain.Side;
import com.stocks.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * §16.8 closed lots, pure: a FIFO replay of one account's executions and {@code position_adjustment} rows that mirrors
 * the engine's lot relief (§12.8 step 5) and yields one {@link Lot} per (buy lot, sell execution) pair, the broker
 * "closed lots" report.
 * <ul>
 *   <li>A BUY opens a lot keyed by its execution id, {@code cost = −net_amount} (buy fees included).</li>
 *   <li>A SELL relieves the open lots of its symbol FIFO by {@code (opened_at, execution id)}: each slice costs
 *       {@code cost_remaining × q / qty_remaining} (HALF_EVEN to cents; a full relief takes the whole remainder),
 *       and gets {@code net_amount × q / qty} of the proceeds (HALF_EVEN; the last slice takes the rest). The slices of
 *       a sell therefore add up to its {@code realized_pnl}; if the ledger disagrees anyway, the difference goes on the
 *       last slice (the ledger is the source of truth).</li>
 *   <li>An adjustment (split, reverse split, dust removal, rename) sets its lot's quantity, cost and symbol to the
 *       {@code *_after} values; a lot it empties closed through a corporate action and is not a trade.</li>
 * </ul>
 */
public final class ClosedLots {

  /** One replay input. Events are replayed in {@link #ORDER} (time, then adjustments before executions, then id). */
  public sealed interface Event permits Execution, Adjustment {
    Instant at();

    long id();
  }

  /** An {@code execution} row; {@code realizedPnl} is null on buys. */
  public record Execution(long id, String symbol, Side side, BigDecimal qty, BigDecimal netAmount,
                          BigDecimal realizedPnl, Instant at) implements Event {}

  /** A {@code position_adjustment} row, keyed by its lot's {@code open_execution_id}. */
  public record Adjustment(long id, long lotExecutionId, String symbolAfter, BigDecimal qtyAfter,
                           BigDecimal costAfter, Instant at) implements Event {}

  /** One closed lot: {@code realizedPnl = proceeds − cost}. */
  public record Lot(long buyExecutionId, long sellExecutionId, String symbol, BigDecimal qty, Instant openedAt,
                    Instant closedAt, BigDecimal cost, BigDecimal proceeds, BigDecimal realizedPnl) {

    public long holdSeconds() {
      return Math.max(0, Duration.between(openedAt, closedAt).toSeconds());
    }

    /** {@code realizedPnl / cost} at scale 10; null for a zero cost. */
    public BigDecimal ret() {
      return cost.signum() > 0 ? realizedPnl.divide(cost, MetricsCalculator.RETURN_SCALE, RoundingMode.HALF_EVEN)
          : null;
    }
  }

  /** Replay order: time, then adjustments (03:45 corporate actions) before executions, then id. */
  public static final Comparator<Event> ORDER = Comparator.comparing(Event::at)
      .thenComparing(e -> e instanceof Execution ? 1 : 0)
      .thenComparingLong(Event::id);

  private static final class Open {
    final long executionId;
    final Instant openedAt;
    String symbol;
    BigDecimal qty;
    BigDecimal cost;

    Open(long executionId, Instant openedAt, String symbol, BigDecimal qty, BigDecimal cost) {
      this.executionId = executionId;
      this.openedAt = openedAt;
      this.symbol = symbol;
      this.qty = qty;
      this.cost = cost;
    }
  }

  private ClosedLots() {}

  /** The closed lots of one account's history, in closing order. */
  public static List<Lot> replay(List<? extends Event> events) {
    List<Event> sorted = new ArrayList<>(events);
    sorted.sort(ORDER);
    Map<Long, Open> open = new LinkedHashMap<>();   // insertion = (opened_at, id) order: FIFO
    List<Lot> out = new ArrayList<>();
    for (Event e : sorted) {
      if (e instanceof Adjustment a) {
        Open lot = open.get(a.lotExecutionId());
        if (lot == null) {
          continue;
        }
        if (a.qtyAfter().signum() <= 0) {
          open.remove(a.lotExecutionId());              // dust or cash in lieu: closed by the corporate action
        } else {
          lot.qty = a.qtyAfter();
          lot.cost = Money.cents(a.costAfter());
          lot.symbol = a.symbolAfter();
        }
      } else if (e instanceof Execution x) {
        if (x.side() == Side.BUY) {
          open.put(x.id(), new Open(x.id(), x.at(), x.symbol(), x.qty(), Money.cents(x.netAmount().negate())));
        } else {
          relieve(open, x, out);
        }
      }
    }
    return out;
  }

  private static void relieve(Map<Long, Open> open, Execution sell, List<Lot> out) {
    BigDecimal left = sell.qty();
    BigDecimal proceedsLeft = Money.cents(sell.netAmount());
    List<Lot> slices = new ArrayList<>();
    var it = open.values().iterator();
    while (it.hasNext() && left.signum() > 0) {
      Open lot = it.next();
      if (!lot.symbol.equals(sell.symbol())) {
        continue;
      }
      BigDecimal q = lot.qty.min(left);
      boolean full = q.compareTo(lot.qty) == 0;
      BigDecimal cost = full ? lot.cost
          : lot.cost.multiply(q).divide(lot.qty, Money.SCALE, RoundingMode.HALF_EVEN);
      left = left.subtract(q);
      BigDecimal proceeds = left.signum() == 0 ? proceedsLeft
          : sell.netAmount().multiply(q).divide(sell.qty(), Money.SCALE, RoundingMode.HALF_EVEN);
      proceedsLeft = proceedsLeft.subtract(proceeds);
      slices.add(new Lot(lot.executionId, sell.id(), sell.symbol(), q, lot.openedAt, sell.at(), cost, proceeds,
          proceeds.subtract(cost)));
      if (full) {
        it.remove();
      } else {
        lot.qty = lot.qty.subtract(q);
        lot.cost = lot.cost.subtract(cost);
      }
    }
    if (!slices.isEmpty() && left.signum() == 0 && sell.realizedPnl() != null) {
      BigDecimal sum = slices.stream().map(Lot::realizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal diff = Money.cents(sell.realizedPnl()).subtract(sum);
      if (diff.signum() != 0) {
        Lot last = slices.removeLast();
        BigDecimal realized = last.realizedPnl().add(diff);
        slices.add(new Lot(last.buyExecutionId(), last.sellExecutionId(), last.symbol(), last.qty(), last.openedAt(),
            last.closedAt(), last.proceeds().subtract(realized), last.proceeds(), realized));
      }
    }
    out.addAll(slices);
  }

  // ------------------------------------------------------------------------------------------- summaries

  /** §16.8 trade summary of the closed lots of a window. */
  public record Summary(int count, Long avgHoldSeconds, Lot best, Lot worst) {
    public static final Summary NONE = new Summary(0, null, null, null);
  }

  /**
   * Count and average hold of {@code lots}; best and worst among those closed at or before {@code bestWorstAsOf}
   * (the feed delay for other players, §16.7), ties to the earliest close.
   */
  public static Summary summarize(List<Lot> lots, Instant bestWorstAsOf) {
    Objects.requireNonNull(bestWorstAsOf, "bestWorstAsOf");
    if (lots.isEmpty()) {
      return Summary.NONE;
    }
    long totalHold = 0;
    Lot best = null;
    Lot worst = null;
    for (Lot l : lots) {
      totalHold += l.holdSeconds();
      if (l.closedAt().isAfter(bestWorstAsOf)) {
        continue;
      }
      if (best == null || l.realizedPnl().compareTo(best.realizedPnl()) > 0
          || (l.realizedPnl().compareTo(best.realizedPnl()) == 0 && l.closedAt().isBefore(best.closedAt()))) {
        best = l;
      }
      if (worst == null || l.realizedPnl().compareTo(worst.realizedPnl()) < 0
          || (l.realizedPnl().compareTo(worst.realizedPnl()) == 0 && l.closedAt().isBefore(worst.closedAt()))) {
        worst = l;
      }
    }
    long avg = BigDecimal.valueOf(totalHold).divide(BigDecimal.valueOf(lots.size()), 0, RoundingMode.HALF_EVEN)
        .longValueExact();
    return new Summary(lots.size(), avg, best, worst);
  }
}
