package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.marketdata.QuoteView;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.engine.write.OrderGroupWriter;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.order.ExpiryCalculator;
import com.stocks.trading.order.HoldCalculator;
import com.stocks.trading.order.NormalizedOrder;
import com.stocks.trading.order.OrderContext;
import com.stocks.trading.order.OrderStateMachine;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.order.SessionOrderMatrix;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The group rules of §11.9 on the engine thread, applied in the same transaction as the change that triggers them
 * (a fill, a cancel, an expiry, a replace), so they are restart-safe: everything they decide is stored.
 *
 * <ul>
 *   <li><b>Working quantity.</b> A child works {@code min(qty − filled, entry.filled − filled)}; with nothing to work
 *       it waits ({@code AWAITING_PARENT}).</li>
 *   <li><b>Activation.</b> The entry's first execution releases its held children (event {@code ACTIVATED}, own
 *       session, expiry and latency window from now); a later entry fill raises their working quantity
 *       ({@code QTY_ADJUSTED}).</li>
 *   <li><b>Entry finished.</b> With fills, its open children are trimmed to the entry's filled quantity (FILLED when
 *       they already sold that much); without fills they are cancelled ({@code PARENT_NOT_FILLED}, or the system
 *       reason that ended the entry).</li>
 *   <li><b>One-cancels-other.</b> The first execution of either leg of an OCO pair (a partial fill is enough) cancels
 *       the other ({@code OCO_FILLED}).</li>
 *   <li><b>One reservation per pair.</b> A SELL pair's shares are reserved once, on one leg; before a pair leg
 *       executes the reservation moves onto it ({@link #prepareFill}), and before one ends it moves to the other
 *       ({@link #beforeTerminal}). SELL children reserve what they work.</li>
 * </ul>
 */
@Component
class GroupHandler {

  /** System reasons that end a whole account's or symbol's orders: the children are cancelled for the same reason. */
  private static final Set<CancelReason> CASCADED = Set.of(CancelReason.ADMIN, CancelReason.PLAYER_FROZEN,
      CancelReason.PLAYER_DISABLED, CancelReason.ACCOUNT_RESET, CancelReason.CORPORATE_ACTION);

  private final OrderStore orders;
  private final OrderWriter writer;
  private final OrderGroupWriter groupWriter;
  private final OrderTracker tracker;
  private final LatencyModel latency;
  private final TradingClock clock;
  private final RulesProperties rules;
  private final HoldCalculator holds;
  private final OrderContext context;
  private final JsonMapper json;

  GroupHandler(OrderStore orders, OrderWriter writer, OrderGroupWriter groupWriter, OrderTracker tracker,
               LatencyModel latency, TradingClock clock, RulesProperties rules, HoldCalculator holds,
               OrderContext context, JsonMapper json) {
    this.orders = orders;
    this.writer = writer;
    this.groupWriter = groupWriter;
    this.tracker = tracker;
    this.latency = latency;
    this.clock = clock;
    this.rules = rules;
    this.holds = holds;
    this.context = context;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------- quantities

  /**
   * The quantity a leg may work now: {@code qty − filled}, and for a child also at most what its entry bought that the
   * child has not sold yet.
   */
  BigDecimal working(OrderRow r) {
    BigDecimal remaining = r.remainingQty();
    if (remaining == null || r.parentOrderId() == null) {
      return remaining;
    }
    BigDecimal bought = orders.byId(r.parentOrderId()).map(OrderRow::filledQty).orElse(Qty.ZERO);
    return remaining.min(bought.subtract(r.filledQty())).max(Qty.ZERO);
  }

  // ------------------------------------------------------------------------------------------- reservations

  /**
   * Before a leg of an OCO pair executes: the pair's reservation moves onto it, so the execution-time guard and the
   * share release of §10.3 see the full reservation. Returns the leg's row to post against.
   */
  OrderRow prepareFill(OrderRow leg) {
    if (!inPair(leg)) {
      return leg;
    }
    BigDecimal cash = leg.reservedCash();
    BigDecimal qty = leg.reservedQty();
    boolean moved = false;
    for (OrderRow s : openPair(leg.groupId())) {
      if (s.id() == leg.id() || (s.reservedCash().signum() == 0 && s.reservedQty().signum() == 0)) {
        continue;
      }
      cash = cash.add(s.reservedCash());
      qty = qty.add(s.reservedQty());
      writer.setReservations(s.id(), Money.ZERO, Qty.ZERO);
      moved = true;
    }
    if (!moved) {
      return leg;
    }
    writer.setReservations(leg.id(), cash, qty);
    return orders.byId(leg.id()).orElseThrow();
  }

  /**
   * Before a leg of an OCO pair ends without executing all of it (cancel, expiry, replace): its reservation moves to
   * the other open leg, which then works alone. A BUY leg passes on at most what the other leg's own hold needs.
   */
  void beforeTerminal(OrderRow leg) {
    if (!inPair(leg) || (leg.reservedCash().signum() == 0 && leg.reservedQty().signum() == 0)) {
      return;
    }
    OrderRow other = openPair(leg.groupId()).stream().filter(s -> s.id() != leg.id()).findFirst().orElse(null);
    if (other == null) {
      return;
    }
    writer.setReservations(leg.id(), Money.ZERO, Qty.ZERO);
    if (other.side() == Side.SELL) {
      BigDecimal w = working(other);
      writer.setReservations(other.id(), Money.ZERO, w == null ? Qty.ZERO : w);
    } else {
      BigDecimal pool = leg.reservedCash().add(other.reservedCash());
      writer.setReservations(other.id(), pool.min(ownBuyHold(other)), Qty.ZERO);
    }
  }

  /**
   * The hold a BUY leg needs on its own (§10.3) for its remaining quantity: at its limit, or for a STOP
   * {@code max(stop, ref) × 102%}.
   */
  BigDecimal ownBuyHold(OrderRow r) {
    BigDecimal remaining = r.remainingQty();
    if (remaining == null || remaining.signum() <= 0) {
      return Money.ZERO;
    }
    var market = context.market(r.symbol(), Et.date(clock.now()));
    QuoteView q = market.quote();
    NormalizedOrder o = new NormalizedOrder(r.clientOrderId(), r.symbol(), r.side(), r.type(), remaining, null,
        r.limitPrice(), r.stopPrice(), r.trailAmount(), r.trailPercent(), r.timeInForce(), r.extendedHours(), null,
        false, Set.of());
    BigDecimal hold = holds.buyHold(o, false, q == null ? null : q.ask(), market.reference(),
        clock.tradeDate(clock.now()));
    return hold == null ? r.reservedCash() : hold;
  }

  /**
   * SELL legs reserve what they work (§11.9): a child its working quantity, an OCO pair the larger of its two
   * working quantities, once, on one leg (the leg that already holds it, else the take profit). Held legs hold
   * nothing.
   */
  void rebalance(long groupId) {
    List<OrderRow> open = orders.legs(groupId).stream().filter(OrderRow::isOpen)
        .filter(r -> !r.held() && r.side() == Side.SELL).toList();
    List<OrderRow> pair = open.stream().filter(GroupHandler::inPair).toList();
    for (OrderRow r : open) {
      if (inPair(r)) {
        continue;
      }
      setReservedQty(r, working(r));
    }
    if (pair.isEmpty()) {
      return;
    }
    BigDecimal needed = pair.stream().map(this::working).reduce(Qty.ZERO, BigDecimal::max);
    OrderRow holder = pair.stream().filter(r -> r.reservedQty().signum() > 0).findFirst()
        .orElse(pair.stream().min(Comparator.comparing(OrderRow::groupRole)).orElseThrow());
    for (OrderRow r : pair) {
      setReservedQty(r, r.id() == holder.id() ? needed : Qty.ZERO);
    }
  }

  private void setReservedQty(OrderRow r, BigDecimal qty) {
    BigDecimal target = qty == null ? Qty.ZERO : qty;
    if (r.reservedQty().compareTo(target) != 0 || r.reservedCash().signum() != 0) {
      writer.setReservations(r.id(), Money.ZERO, target);
    }
  }

  // ------------------------------------------------------------------------------------------- after a change

  /**
   * After {@code leg} changed in this transaction: {@code executed} when it just executed (a fill), and
   * {@code cascadeOrNull} the reason it was cancelled, if it was. Applies the group rules (class comment), then the
   * SELL reservations.
   */
  void afterChange(OrderRow leg, boolean executed, CancelReason cascadeOrNull, Instant now, EngineTx tx) {
    if (!leg.inGroup()) {
      return;
    }
    long groupId = leg.groupId();
    List<OrderRow> legs = current(groupId);
    OrderRow entry = legs.stream().filter(r -> r.groupRole() == OrderGroupRole.ENTRY).findFirst().orElse(null);
    if (entry != null) {
      followEntry(entry, executed && entry.id() == leg.id(), cascadeOrNull, now, tx);
      legs = current(groupId);
    }
    List<OrderRow> pair = legs.stream().filter(GroupHandler::inPair).toList();
    OrderRow executedLeg = pair.stream().filter(r -> r.filledQty().signum() > 0).findFirst().orElse(null);
    if (executedLeg != null) {
      for (OrderRow other : pair) {
        if (other.id() != executedLeg.id() && other.isOpen()) {
          Map<String, Object> detail = new LinkedHashMap<>();
          detail.put("sibling", executedLeg.publicId());
          cancel(other, CancelReason.OCO_FILLED, detail, now, tx);
        }
      }
    }
    rebalance(groupId);
    groupWriter.touch(groupId, now);
  }

  /** The children follow their entry (activation, working quantity, trim, cancel). */
  private void followEntry(OrderRow entry, boolean entryExecuted, CancelReason cascadeOrNull, Instant now,
                           EngineTx tx) {
    List<OrderRow> children = orders.legs(entry.groupId()).stream()
        .filter(r -> r.isOpen() && r.parentOrderId() != null && r.parentOrderId() == entry.id()).toList();
    BigDecimal bought = entry.filledQty();
    if (bought.signum() == 0) {
      if (!entry.isOpen()) {
        CancelReason reason = cascadeOrNull != null && CASCADED.contains(cascadeOrNull) ? cascadeOrNull
            : CancelReason.PARENT_NOT_FILLED;
        for (OrderRow c : children) {
          Map<String, Object> detail = new LinkedHashMap<>();
          detail.put("parent", entry.publicId());
          cancel(c, reason, detail, now, tx);
        }
      }
      return;
    }
    for (OrderRow c : children) {
      if (c.held()) {
        release(c, entry, now, tx);
      } else if (entryExecuted) {
        BigDecimal w = working(c);
        writer.event(c.id(), OrderEventType.QTY_ADJUSTED, c.status(), now,
            json.writeValueAsString(Map.of("workingQty", OrderValidator.qtyOut(w), "entryOrderId",
                entry.publicId())));
        tx.publish(new OrderChanged(c.accountId(), c.playerId(), c.publicId(), OrderEventType.QTY_ADJUSTED,
            c.status(), now));
      }
      if (!entry.isOpen() && c.qty().compareTo(bought) != 0) {
        trim(orders.byId(c.id()).orElseThrow(), bought, entry, now, tx);
      }
    }
  }

  /** A held child released by its entry's execution: its own session, expiry and latency window from now. */
  private void release(OrderRow child, OrderRow entry, Instant now, EngineTx tx) {
    SessionState session = clock.session(now);
    SessionOrderMatrix.Acceptance a = SessionOrderMatrix.accept(clock.calendar(), child.type(), false,
        child.timeInForce(), session, now);
    Instant eligibleAt = a.eligibleBase().plus(latency.latency(child.publicId(), 0));
    ExpiryCalculator.Expiry expiry = ExpiryCalculator.compute(clock.calendar(), rules, now, false,
        child.timeInForce());
    writer.release(child.id(), eligibleAt, a.waitReason(), expiry.sessionDate(), expiry.expiresAt());
    BigDecimal w = entry.filledQty().subtract(child.filledQty()).min(child.remainingQty()).max(Qty.ZERO);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("entryOrderId", entry.publicId());
    detail.put("workingQty", OrderValidator.qtyOut(w));
    detail.put("eligibleAt", eligibleAt.toString());
    writer.event(child.id(), OrderEventType.ACTIVATED, OrderStatus.PENDING, now, json.writeValueAsString(detail));
    if (a.queued()) {
      writer.event(child.id(), OrderEventType.QUEUED, OrderStatus.PENDING, now,
          json.writeValueAsString(Map.of("waitReason", a.waitReason().name())));
    }
    tx.publish(new OrderChanged(child.accountId(), child.playerId(), child.publicId(), OrderEventType.ACTIVATED,
        OrderStatus.PENDING, now));
    long id = child.id();
    tx.afterCommit(() -> orders.byId(id).ifPresent(tracker::track));
  }

  /** A child follows its finished entry's filled quantity; FILLED when it already sold that much. */
  private void trim(OrderRow c, BigDecimal bought, OrderRow entry, Instant now, EngineTx tx) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("qty", OrderValidator.qtyOut(bought));
    detail.put("entryOrderId", entry.publicId());
    if (c.filledQty().compareTo(bought) >= 0) {
      OrderStateMachine.transition(c.status() == OrderStatus.PENDING ? OrderStatus.WORKING : c.status(),
          OrderStatus.FILLED);
      writer.filledByTrim(c.id(), bought, now);
      writer.event(c.id(), OrderEventType.QTY_ADJUSTED, OrderStatus.FILLED, now, json.writeValueAsString(detail));
      tx.afterCommit(() -> tracker.untrack(c.id()));
      tx.publish(new OrderChanged(c.accountId(), c.playerId(), c.publicId(), OrderEventType.QTY_ADJUSTED,
          OrderStatus.FILLED, now));
      return;
    }
    writer.setQty(c.id(), bought);
    detail.put("workingQty", OrderValidator.qtyOut(bought.subtract(c.filledQty())));
    writer.event(c.id(), OrderEventType.QTY_ADJUSTED, c.status(), now, json.writeValueAsString(detail));
    tx.publish(new OrderChanged(c.accountId(), c.playerId(), c.publicId(), OrderEventType.QTY_ADJUSTED, c.status(),
        now));
  }

  /** A system cancel of one leg by a group rule (never cancel latency, §11.10). */
  void cancel(OrderRow r, CancelReason reason, Map<String, Object> extra, Instant now, EngineTx tx) {
    OrderRow fresh = orders.byId(r.id()).orElseThrow();
    if (!fresh.isOpen()) {
      return;
    }
    beforeTerminal(fresh);
    OrderStateMachine.transition(fresh.status(), OrderStatus.CANCELLED);
    writer.finish(fresh.id(), OrderStatus.CANCELLED, reason.name(), now);
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reason", reason.name());
    detail.put("by", "SYSTEM");
    detail.putAll(extra);
    writer.event(fresh.id(), OrderEventType.CANCELLED, OrderStatus.CANCELLED, now, json.writeValueAsString(detail));
    tx.afterCommit(() -> tracker.untrack(fresh.id()));
    tx.publish(new OrderChanged(fresh.accountId(), fresh.playerId(), fresh.publicId(), OrderEventType.CANCELLED,
        OrderStatus.CANCELLED, now));
    tx.publish(new AccountChanged(fresh.accountId(), fresh.playerId(), "ORDER_CANCELLED", now));
  }

  // ------------------------------------------------------------------------------------------- helpers

  /** The group's current legs (REPLACED ones left out), oldest first. */
  List<OrderRow> current(long groupId) {
    return orders.legs(groupId).stream().filter(r -> r.status() != OrderStatus.REPLACED).toList();
  }

  private List<OrderRow> openPair(long groupId) {
    return orders.legs(groupId).stream().filter(r -> r.isOpen() && inPair(r)).toList();
  }

  /** A live (not held) leg of an OCO pair. */
  static boolean inPair(OrderRow r) {
    return r.inGroup() && r.groupRole() != null && r.groupRole().inOcoPair() && !r.held();
  }

}
