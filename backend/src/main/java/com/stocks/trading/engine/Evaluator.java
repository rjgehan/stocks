package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.Crypto;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.SessionState;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.Print;
import com.stocks.contract.marketdata.RefreshPriority;
import com.stocks.trading.config.RulesProperties;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.order.OrderStateMachine;
import com.stocks.trading.order.OrderTypes;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.order.SessionOrderMatrix;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Works one open order on the engine thread (§11.3, §12.2–§12.7), inside the transaction its caller opened:
 * expiry, pause and resume at window edges, activation once {@code eligible_at} passes, halts, the trigger of an armed
 * stop ({@link TriggerEvaluator}), the anti-look-ahead executable quote, the fill decision ({@link FillModel}; a
 * triggered stop works as a MARKET or LIMIT order) and posting ({@link PostingService}).
 */
@Component
class Evaluator {

  static final Duration REFRESH_EVERY = Duration.ofSeconds(2);

  private final OrderStore orders;
  private final AccountStore accounts;
  private final OrderWriter writer;
  private final PostingService posting;
  private final MarketDataService marketData;
  private final OrderValidator validator;
  private final LatencyModel latency;
  private final TradingClock clock;
  private final RulesProperties rules;
  private final OrderHandler handler;
  private final TriggerEvaluator triggers;
  private final JsonMapper json;
  private final GroupHandler groups;
  private final Map<String, Instant> lastRefresh = new HashMap<>();

  Evaluator(OrderStore orders, AccountStore accounts, OrderWriter writer, PostingService posting,
            MarketDataService marketData, OrderValidator validator, LatencyModel latency, TradingClock clock,
            RulesProperties rules, OrderHandler handler, TriggerEvaluator triggers, JsonMapper json,
            GroupHandler groups) {
    this.orders = orders;
    this.accounts = accounts;
    this.writer = writer;
    this.posting = posting;
    this.marketData = marketData;
    this.validator = validator;
    this.latency = latency;
    this.clock = clock;
    this.rules = rules;
    this.handler = handler;
    this.triggers = triggers;
    this.json = json;
    this.groups = groups;
  }

  /** The order's window: REGULAR, plus PRE/POST for extended-hours limits (§11.3), optionally only 08:00–17:00. */
  boolean windowOpen(boolean extendedHours, SessionState session, Instant now) {
    if (!SessionOrderMatrix.windowOpen(extendedHours, session.type())) {
      return false;
    }
    if (extendedHours && session.type() != SessionType.REGULAR && rules.extHours().requireRealQuotes()) {
      LocalTime t = Et.time(now);
      return !t.isBefore(LocalTime.of(8, 0)) && t.isBefore(LocalTime.of(17, 0));
    }
    return true;
  }

  /** Expires the order if {@code expires_at ≤ now}; true if it did. */
  boolean expireIfDue(OrderRow r, Instant now, EngineTx tx) {
    if (now.isBefore(r.expiresAt())) {
      return false;
    }
    OrderStateMachine.transition(r.status(), OrderStatus.EXPIRED);
    if (r.inGroup()) {
      groups.beforeTerminal(r);
    }
    String reason = r.timeInForce() + " expiry";
    writer.finish(r.id(), OrderStatus.EXPIRED, reason, now);
    writer.event(r.id(), OrderEventType.EXPIRED, OrderStatus.EXPIRED, now,
        json.writeValueAsString(Map.of("expiresAt", r.expiresAt().toString())));
    tx.afterCommit(() -> handler.untrack(r.id()));
    tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.EXPIRED,
        OrderStatus.EXPIRED, now));
    tx.publish(new AccountChanged(r.accountId(), r.playerId(), "ORDER_EXPIRED", now));
    if (r.inGroup()) {
      groups.afterChange(r, false, null, now, tx);
    }
    return true;
  }

  /**
   * Window bookkeeping without filling (transitions and catch-up, §9.4): expiry, PAUSED when the window closed,
   * RESUMED with a fresh latency window when it reopened. Returns the order's row after the changes, or empty if it
   * is no longer open.
   */
  Optional<OrderRow> lifecycle(OpenOrderIndex.Entry e, SessionState session, Instant now, EngineTx tx) {
    OrderRow r = orders.byId(e.id).orElse(null);
    if (r == null || !r.isOpen()) {
      tx.afterCommit(() -> handler.untrack(e.id));
      return Optional.empty();
    }
    if (r.cancelRequestedAt() != null && !now.isBefore(r.cancelEffectiveAt())) {
      handler.cancelOpen(r, CancelReason.USER, r.playerId(), now, tx);   // the requested cancel takes effect (§11.10)
      return Optional.empty();
    }
    if (expireIfDue(r, now, tx)) {
      return Optional.empty();
    }
    boolean open = windowOpen(r.extendedHours(), session, now);
    if (!open) {
      if (r.status() != OrderStatus.PENDING && r.waitReason() != WaitReason.SESSION_CLOSED) {
        writer.setWaitReason(r.id(), WaitReason.SESSION_CLOSED);
        writer.event(r.id(), OrderEventType.PAUSED, r.status(), now,
            json.writeValueAsString(Map.of("session", session.type().name())));
        tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.PAUSED, r.status(), now));
        tx.afterCommit(() -> {
          e.waitReason = WaitReason.SESSION_CLOSED;
          e.reopenPending = true;
        });
      }
      return Optional.empty();
    }
    if (r.waitReason() == WaitReason.SESSION_CLOSED) {
      int activationNo = orders.resumeCount(r.id()) + 1;
      Instant opened = session.startedAt() == null ? now : session.startedAt();
      Instant eligible = opened.plus(latency.latency(r.publicId(), activationNo));
      writer.setEligible(r.id(), eligible, WaitReason.LATENCY);
      writer.event(r.id(), OrderEventType.RESUMED, r.status(), now,
          json.writeValueAsString(Map.of("eligibleAt", eligible.toString())));
      tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.RESUMED, r.status(), now));
      tx.afterCommit(() -> {
        e.eligibleAt = eligible;
        e.waitReason = WaitReason.LATENCY;
        e.reopenPending = true;
      });
      r = orders.byId(r.id()).orElseThrow();
    }
    return Optional.of(r);
  }

  /** Full evaluation: lifecycle, activation, then fills (§12.2 {@code evaluateSymbol}, per order). */
  void evaluate(OpenOrderIndex.Entry e, SessionState session, Instant now, List<PrintBuffer.Seq> prints,
                long printCursorAfter, EngineTx tx) {
    Optional<OrderRow> maybe = lifecycle(e, session, now, tx);
    if (maybe.isEmpty()) {
      return;
    }
    OrderRow r = maybe.get();
    if (now.isBefore(r.eligibleAt())) {
      return;
    }
    boolean arriving = e.reopenPending;
    // §12.7 opening gap: the first evaluation of a session (an order queued for the open, a resume, after a halt)
    boolean sessionStart = e.reopenPending || (r.status() == OrderStatus.PENDING && e.queuedForOpen);
    if (r.status() == OrderStatus.PENDING) {
      writer.activate(r.id(), now);
      writer.event(r.id(), OrderEventType.WORKING, OrderStatus.WORKING, now, null);
      tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.WORKING,
          OrderStatus.WORKING, now));
      r = orders.byId(r.id()).orElseThrow();
      arriving = true;
    } else if (r.waitReason() == WaitReason.LATENCY) {
      writer.setWaitReason(r.id(), null);
      r = orders.byId(r.id()).orElseThrow();
    }
    final OrderRow row = r;
    tx.afterCommit(() -> {
      e.status = row.status();
      e.lastEvaluatedAt = now;
      e.printCursor = printCursorAfter;
    });

    if (r.parentOrderId() != null && groups.working(r).signum() <= 0) {
      setWait(r, WaitReason.AWAITING_PARENT, null, tx, now);   // a child with nothing bought to sell yet (§11.9)
      return;
    }
    if (marketData.isHalted(r.symbol())) {
      // a NEEDS_ADMIN split also reads as halted (§14): name the wait after the corporate-action hold then
      WaitReason why = orders.onCorporateActionHold(r.symbol(), Et.date(now)) ? WaitReason.CORPORATE_ACTION_HOLD
          : WaitReason.HALTED;
      setWait(r, why, null, tx, now);
      tx.afterCommit(() -> e.reopenPending = true);
      return;
    }
    if (OrderTypes.isStop(r.type()) && !r.triggered()) {
      armed(e, r, now, prints, sessionStart, tx);
      return;
    }
    Optional<ExecQuote> quote = marketData.executableQuote(r.symbol(), r.eligibleAt(), session.type());
    boolean resting = OrderTypes.usesLimit(r.type()) && r.hasRested() && !arriving;
    List<Print> eligiblePrints = prints.stream().map(PrintBuffer.Seq::print)
        .filter(p -> !p.observedAt().isBefore(row.eligibleAt())).toList();

    if (quote.isEmpty() && !resting) {
      setWait(r, WaitReason.AWAITING_QUOTE, OrderEventType.AWAITING_QUOTE, tx, now);
      requestRefresh(r.symbol(), now);
      return;
    }
    ExecQuote q = quote.orElse(null);
    BigDecimal remaining = remainingFor(r, q, now);
    if (remaining != null && r.parentOrderId() != null) {
      remaining = remaining.min(groups.working(r));           // a child works what its entry bought (§11.9)
    }
    if (remaining != null && remaining.compareTo(rules.fractional().minQty()) < 0) {
      handler.cancelOpen(r, CancelReason.DUST, 0, now, tx);
      return;
    }
    // a triggered STOP / TRAILING_STOP works as a MARKET order, a triggered STOP_LIMIT as a LIMIT (§12.7)
    FillModel.OrderView view = new FillModel.OrderView(r.side(), OrderTypes.fillsAs(r.type()),
        r.fractional() || Crypto.isCrypto(r.symbol()),   // a crypto pair takes one fill at the touch (§29)
        remaining, r.limitPrice(), r.collarPrice());
    FillModel.Decision d;
    if (resting) {
      d = FillModel.resting(view, q, eligiblePrints, rules.fill().restingRequiresPrint());
    } else {
      boolean opening = e.queuedForOpen && !OrderTypes.isStop(r.type()) && r.filledQty().signum() == 0;
      d = FillModel.marketable(view, q, opening, rules.marketCollarPct());
    }
    if (d.collar() != null && r.collarPrice() == null) {
      writer.setCollar(r.id(), d.collar());
    }
    if (d.rest() && !r.hasRested()) {
      writer.setRested(r.id());
      tx.afterCommit(() -> e.hasRested = true);
    }
    tx.afterCommit(() -> {
      e.reopenPending = false;
      e.awaitingQuoteNoted = false;
    });
    OrderRow current = orders.byId(r.id()).orElseThrow();
    if (current.inGroup() && !d.legs().isEmpty()) {
      current = groups.prepareFill(current);                    // the OCO pair's reservation moves here (§11.9)
    }
    PostingService.Outcome outcome = d.legs().isEmpty() ? null
        : posting.post(current, d.legs(), q != null ? q : syntheticRef(current, d), now, tx);
    if (outcome != null && current.inGroup()) {
      groups.afterChange(current, outcome.executions() > 0,
          outcome.cancelledRemainder() ? CancelReason.INSUFFICIENT_BUYING_POWER_AT_EXECUTION : null, now, tx);
    }
    if (outcome != null && OrderStateMachine.isTerminal(outcome.status())) {
      tx.afterCommit(() -> handler.untrack(e.id));
      return;
    }
    OrderRow after = orders.byId(r.id()).orElseThrow();
    if (d.beyondCollar()) {
      setWait(after, WaitReason.BEYOND_COLLAR, OrderEventType.BEYOND_COLLAR, tx, now);
    } else if (OrderTypes.usesLimit(after.type())) {
      setWait(after, WaitReason.LIMIT_NOT_REACHED, null, tx, now);
    } else if (after.waitReason() != null) {
      setWait(after, null, null, tx, now);
    }
    tx.afterCommit(() -> orders.byId(e.id).ifPresent(fresh -> e.refresh(fresh)));
  }

  /**
   * An armed stop (§12.7): the trigger decision only, never a fill in the same step. {@code opening}: the first
   * evaluation of a session (activation of an order queued for the open, a resume at the open, after a halt), when a
   * quote already through the stop triggers at once (opening gap); that check stays pending until a quote was
   * available. A stop entered during the session is not an opening at its activation.
   */
  private void armed(OpenOrderIndex.Entry e, OrderRow r, Instant now, List<PrintBuffer.Seq> prints, boolean opening,
                     EngineTx tx) {
    TriggerEvaluator.Result t = triggers.evaluate(e, r, now, prints.stream().map(PrintBuffer.Seq::print).toList(),
        opening, tx);
    if (t.quoteMissing()) {
      requestRefresh(r.symbol(), now);
    }
    if (!t.triggered()) {
      boolean keepOpening = opening && t.quoteMissing();
      tx.afterCommit(() -> {
        e.reopenPending = keepOpening;
        e.awaitingQuoteNoted = false;
      });
    }
    tx.afterCommit(() -> orders.byId(e.id).ifPresent(e::refresh));
  }

  /** The quantity to work now: the remaining shares, or a notional order sized from the touch (§11.1). */
  BigDecimal remainingFor(OrderRow r, ExecQuote q, Instant now) {
    if (r.notional() == null) {
      return r.remainingQty();
    }
    if (q == null) {
      return null;
    }
    if (r.side() == Side.BUY) {
      return validator.notionalBuyQty(r.symbol(), r.notional(), q.ask(), clock.tradeDate(now));
    }
    BigDecimal held = accounts.position(r.accountId(), r.symbol()).map(PositionRow::qty).orElse(Qty.ZERO);
    BigDecimal available = held.subtract(orders.reservedSellQty(r.accountId(), r.symbol(), r.id())).max(Qty.ZERO);
    return Rounding.NOTIONAL_QTY.divide(r.notional(), q.bid()).min(available);
  }

  /** A reference "quote" for a print-only resting fill in the IEX-dark windows (§12.5). */
  private static ExecQuote syntheticRef(OrderRow r, FillModel.Decision d) {
    BigDecimal px = d.legs().get(0).price();
    return new ExecQuote(r.symbol(), px, 0, px, 0, px, null, null,
        com.stocks.common.domain.DataQuality.REALTIME_CONSOLIDATED, "prints", false);
  }

  /** Sets a wait reason (and writes its event once per episode, §11.5). */
  private void setWait(OrderRow r, WaitReason reason, OrderEventType eventOrNull, EngineTx tx, Instant now) {
    if (r.waitReason() == reason) {
      return;
    }
    writer.setWaitReason(r.id(), reason);
    if (eventOrNull != null) {
      writer.event(r.id(), eventOrNull, r.status(), now, null);
      tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), eventOrNull, r.status(), now));
    }
  }

  /** Forgets the in-memory market observations (refresh throttle, last consolidated prints): a restart (§12.9). */
  void forget() {
    lastRefresh.clear();
    triggers.clear();
  }

  /** {@code ORDER_CRITICAL} refresh for a symbol awaiting a quote, at most once per 2 s (§12.2). */
  void requestRefresh(String symbol, Instant now) {
    Instant last = lastRefresh.get(symbol);
    if (last != null && Duration.between(last, now).compareTo(REFRESH_EVERY) < 0) {
      return;
    }
    lastRefresh.put(symbol, now);
    try {
      marketData.requestRefresh(symbol, RefreshPriority.ORDER_CRITICAL);
    } catch (RuntimeException ignored) {
      // best effort: the sweep asks again
    }
  }
}
