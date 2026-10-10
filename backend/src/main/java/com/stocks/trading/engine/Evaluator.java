package com.stocks.trading.engine;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
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
 * Works one open order on the engine thread (§11.3, §12.2–§12.6), inside the transaction its caller opened:
 * expiry, pause and resume at window edges, activation once {@code eligible_at} passes, halts, the anti-look-ahead
 * executable quote, the fill decision ({@link FillModel}) and posting ({@link PostingService}).
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
  private final JsonMapper json;
  private final Map<String, Instant> lastRefresh = new HashMap<>();

  Evaluator(OrderStore orders, AccountStore accounts, OrderWriter writer, PostingService posting,
            MarketDataService marketData, OrderValidator validator, LatencyModel latency, TradingClock clock,
            RulesProperties rules, OrderHandler handler, JsonMapper json) {
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
    this.json = json;
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
    String reason = r.timeInForce() + " expiry";
    writer.finish(r.id(), OrderStatus.EXPIRED, reason, now);
    writer.event(r.id(), OrderEventType.EXPIRED, OrderStatus.EXPIRED, now,
        json.writeValueAsString(Map.of("expiresAt", r.expiresAt().toString())));
    tx.afterCommit(() -> handler.untrack(r.id()));
    tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.EXPIRED,
        OrderStatus.EXPIRED, now));
    tx.publish(new AccountChanged(r.accountId(), r.playerId(), "ORDER_EXPIRED", now));
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

    if (marketData.isHalted(r.symbol())) {
      setWait(r, WaitReason.HALTED, null, tx, now);
      tx.afterCommit(() -> e.reopenPending = true);
      return;
    }
    Optional<ExecQuote> quote = marketData.executableQuote(r.symbol(), r.eligibleAt(), session.type());
    boolean resting = r.type() == OrderType.LIMIT && r.hasRested() && !arriving;
    List<Print> eligiblePrints = prints.stream().map(PrintBuffer.Seq::print)
        .filter(p -> !p.observedAt().isBefore(row.eligibleAt())).toList();

    if (quote.isEmpty() && !resting) {
      setWait(r, WaitReason.AWAITING_QUOTE, OrderEventType.AWAITING_QUOTE, tx, now);
      requestRefresh(r.symbol(), now);
      return;
    }
    ExecQuote q = quote.orElse(null);
    BigDecimal remaining = remainingFor(r, q, now);
    if (remaining != null && remaining.compareTo(rules.fractional().minQty()) < 0) {
      handler.cancelOpen(r, CancelReason.DUST, 0, now, tx);
      return;
    }
    FillModel.OrderView view = new FillModel.OrderView(r.side(), r.type(), r.fractional(), remaining,
        r.limitPrice(), r.collarPrice());
    FillModel.Decision d;
    if (resting) {
      d = FillModel.resting(view, q, eligiblePrints, rules.fill().restingRequiresPrint());
    } else {
      boolean opening = e.queuedForOpen && r.filledQty().signum() == 0;
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
    PostingService.Outcome outcome = d.legs().isEmpty() ? null
        : posting.post(current, d.legs(), q != null ? q : syntheticRef(current, d), now, tx);
    if (outcome != null && OrderStateMachine.isTerminal(outcome.status())) {
      tx.afterCommit(() -> handler.untrack(e.id));
      return;
    }
    OrderRow after = orders.byId(r.id()).orElseThrow();
    if (d.beyondCollar()) {
      setWait(after, WaitReason.BEYOND_COLLAR, OrderEventType.BEYOND_COLLAR, tx, now);
    } else if (after.type() == OrderType.LIMIT) {
      setWait(after, WaitReason.LIMIT_NOT_REACHED, null, tx, now);
    } else if (after.waitReason() != null) {
      setWait(after, null, null, tx, now);
    }
    tx.afterCommit(() -> orders.byId(e.id).ifPresent(fresh -> e.refresh(fresh)));
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
      return validator.notionalBuyQty(r.notional(), q.ask(), clock.tradeDate(now));
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
