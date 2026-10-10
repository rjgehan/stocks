package com.stocks.trading.engine;

import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Rounding;
import com.stocks.contract.marketdata.ExecQuote;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.Print;
import com.stocks.trading.engine.write.OrderWriter;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stops and trailing stops (§12.7). Runs on the engine thread, in REGULAR only (a stop's window), for WORKING
 * STOP, STOP_LIMIT and TRAILING_STOP orders that have not triggered yet ({@code wait_reason = NOT_TRIGGERED}).
 *
 * <ul>
 *   <li><b>Trigger source.</b> Eligible consolidated prints drained since the order's last evaluation and observed at
 *       or after its {@code eligible_at} (and after the engine loaded it, so a restart never triggers on prints from
 *       before it). A SELL stop triggers on a print at or below the stop, a BUY stop on one at or above it. If the
 *       symbol has had no consolidated print in the last 15 s (or has no consolidated stream at all), the executable
 *       quote is used instead: SELL when {@code bid ≤ stop}, BUY when {@code ask ≥ stop} [U: Webull's basis]. The
 *       executable quote is anti-look-ahead (§12.3): confirmed at or after the same instant.</li>
 *   <li><b>Opening gap.</b> At the first evaluation of a session (an order queued for the open, a GTC order resumed at
 *       the open, or any order after a halt), a quote already through the stop also triggers at once.</li>
 *   <li><b>Trailing stop.</b> The watermark starts at activation at the first eligible consolidated print, else (quote
 *       mode) at the touch the trigger compares (the bid for a SELL, the ask for a BUY), else at the executable mid;
 *       then {@code hwm = max(hwm, p)} with {@code stop = TICK_DOWN(hwm − amount)} or
 *       {@code TICK_DOWN(hwm × (1 − pct/100))} for a SELL, and {@code lwm = min(lwm, p)} with
 *       {@code stop = TICK_UP(lwm + amount)} or {@code TICK_UP(lwm × (1 + pct/100))} for a BUY. Prints are taken in
 *       order, so a dip before a new high never triggers against the later high. In quote mode the watermark follows
 *       the bid (SELL) or the ask (BUY). {@code watermark_price} and {@code stop_price} are written at most once per
 *       second, {@code TRAIL_UPDATED} at most once per 10 s; a restart continues from the persisted watermark.</li>
 *   <li><b>On trigger:</b> {@code triggered = true}, {@code triggered_at}, the {@code TRIGGERED} event
 *       {@code {price, basis, stopPrice, watermark?, eligibleAt}}, and {@code eligible_at = now + latency}; the order
 *       then works as a MARKET (STOP, TRAILING_STOP; the collar is set at the first post-trigger evaluation) or as a
 *       LIMIT with arriving semantics (STOP_LIMIT) through the ordinary {@link FillModel}. Its market-data lease drops
 *       from {@code ORDER_TRIGGER} to {@code ORDER_RESTING}.</li>
 * </ul>
 * Halted symbols are never evaluated (the {@link Evaluator} stops first), and catch-up never runs this: transitions
 * only expire and pause (§9.4).
 */
@Component
class TriggerEvaluator {

  /** No consolidated print for this long: the executable quote decides instead (§12.7). */
  static final Duration PRINT_FALLBACK = Duration.ofSeconds(15);
  /** A trailing stop's watermark and stop are written at most this often (§12.7). */
  static final Duration PERSIST_EVERY = Duration.ofSeconds(1);
  /** {@code TRAIL_UPDATED} is throttled to one per this interval (§11.5). */
  static final Duration TRAIL_EVENT_EVERY = Duration.ofSeconds(10);

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  /** What triggered the stop (the {@code basis} of the {@code TRIGGERED} event). */
  enum Basis { PRINT, QUOTE, OPENING_QUOTE }

  /**
   * In-memory trigger state of one open stop order, carried on its {@link OpenOrderIndex.Entry} and changed only after
   * a commit. {@code trackedAt} is when the engine loaded the order (acceptance, or the boot that reloaded it): prints
   * observed before it are never trigger evidence.
   */
  static final class State {
    final Instant trackedAt;
    BigDecimal watermark;
    BigDecimal stop;
    BigDecimal persistedWatermark;
    BigDecimal persistedStop;
    Instant persistedAt;
    BigDecimal eventStop;
    Instant trailEventAt;

    State(Instant trackedAt, BigDecimal watermark, BigDecimal stop) {
      this.trackedAt = trackedAt;
      this.watermark = watermark;
      this.stop = stop;
      this.persistedWatermark = watermark;
      this.persistedStop = stop;
      this.eventStop = stop;
    }

    /**
     * The same watermark and stop, tracked again from {@code at}: an engine resume after a halt (§12.9), when nothing
     * observed while the engine was halted may become trigger evidence.
     */
    State retrackedAt(Instant at) {
      State s = new State(at, watermark, stop);
      s.persistedWatermark = persistedWatermark;
      s.persistedStop = persistedStop;
      s.persistedAt = persistedAt;
      s.eventStop = eventStop;
      s.trailEventAt = trailEventAt;
      return s;
    }
  }

  /**
   * The pure decision for one evaluation. {@code triggered}: the price and basis that triggered it. {@code watermark}
   * and {@code stop}: a trailing stop's state after this evaluation (null until it has a starting price); for STOP and
   * STOP_LIMIT {@code stop} is the client's stop price.
   */
  record Outcome(boolean triggered, BigDecimal price, Basis basis, BigDecimal watermark, BigDecimal stop) {}

  /** The order's trigger parameters. {@code stopPrice}: STOP and STOP_LIMIT only. */
  record Params(Side side, OrderType type, BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent) {}

  /**
   * One evaluation's result for the {@link Evaluator}: {@code triggered}, and {@code quoteMissing} when the decision
   * needed an executable quote and none qualified (the caller asks market data for a refresh and keeps the opening
   * check pending).
   */
  record Result(boolean triggered, boolean quoteMissing) {}

  private final OrderWriter writer;
  private final OrderStore orders;
  private final MarketDataService marketData;
  private final LatencyModel latency;
  private final JsonMapper json;
  private final Map<String, Instant> lastConsolidated = new ConcurrentHashMap<>();

  TriggerEvaluator(OrderWriter writer, OrderStore orders, MarketDataService marketData, LatencyModel latency,
                   JsonMapper json) {
    this.writer = writer;
    this.orders = orders;
    this.marketData = marketData;
    this.latency = latency;
    this.json = json;
  }

  // ------------------------------------------------------------------------------------------- pure

  /**
   * Decides one evaluation (pure). {@code prints}: prices of the eligible consolidated prints since the last
   * evaluation, in arrival order. {@code quote}: the executable quote, or null. {@code quoteMode}: no consolidated
   * print in the last 15 s, so the quote is the trigger source; {@code opening}: the first evaluation of a session,
   * when a quote through the stop also triggers.
   */
  static Outcome decide(Params o, BigDecimal watermark, BigDecimal trailStop, List<BigDecimal> prints,
                        ExecQuote quote, boolean quoteMode, boolean opening) {
    boolean buy = o.side() == Side.BUY;
    BigDecimal touch = quote == null ? null : buy ? quote.ask() : quote.bid();
    if (touch != null && touch.signum() <= 0) {
      touch = null;
    }
    if (o.type() != OrderType.TRAILING_STOP) {
      BigDecimal stop = o.stopPrice();
      for (BigDecimal p : prints) {
        if (crosses(buy, p, stop)) {
          return new Outcome(true, p, Basis.PRINT, null, stop);
        }
      }
      if (touch != null && (quoteMode || opening) && crosses(buy, touch, stop)) {
        return new Outcome(true, touch, quoteMode ? Basis.QUOTE : Basis.OPENING_QUOTE, null, stop);
      }
      return new Outcome(false, null, null, null, stop);
    }

    BigDecimal wm = watermark;
    BigDecimal stop = trailStop;
    int from = 0;
    if (wm == null) {
      if (!prints.isEmpty()) {
        wm = prints.get(0);
        from = 1;
      } else if (quoteMode && touch != null) {
        // quote mode compares the touch with the stop: seeding at the mid would trigger on half the spread alone
        wm = touch.setScale(Prices.SCALE, RoundingMode.HALF_EVEN);
      } else if (twoSided(quote)) {
        wm = quote.mid().setScale(Prices.SCALE, RoundingMode.HALF_EVEN);
      } else {
        return new Outcome(false, null, null, null, null);     // no starting price yet
      }
      stop = trailingStop(o, wm);
    } else if (stop == null) {
      stop = trailingStop(o, wm);
    }
    for (int i = from; i < prints.size(); i++) {
      BigDecimal p = prints.get(i);
      wm = buy ? wm.min(p) : wm.max(p);
      stop = trailingStop(o, wm);
      if (crosses(buy, p, stop)) {
        return new Outcome(true, p, Basis.PRINT, wm, stop);
      }
    }
    if (touch != null && (quoteMode || opening)) {
      if (quoteMode) {
        wm = buy ? wm.min(touch) : wm.max(touch);
        stop = trailingStop(o, wm);
      }
      if (crosses(buy, touch, stop)) {
        return new Outcome(true, touch, quoteMode ? Basis.QUOTE : Basis.OPENING_QUOTE, wm, stop);
      }
    }
    return new Outcome(false, null, null, wm, stop);
  }

  /**
   * A trailing stop's stop for a watermark: SELL {@code TICK_DOWN(wm − amount)} / {@code TICK_DOWN(wm × (1 −
   * pct/100))}, BUY {@code TICK_UP(wm + amount)} / {@code TICK_UP(wm × (1 + pct/100))}. A SELL stop that would be at or
   * below zero is null (it can never trigger).
   */
  static BigDecimal trailingStop(Params o, BigDecimal watermark) {
    boolean buy = o.side() == Side.BUY;
    BigDecimal raw;
    if (o.trailAmount() != null) {
      raw = buy ? watermark.add(o.trailAmount()) : watermark.subtract(o.trailAmount());
    } else {
      BigDecimal f = o.trailPercent().divide(HUNDRED, 10, RoundingMode.HALF_UP);
      raw = watermark.multiply(buy ? BigDecimal.ONE.add(f) : BigDecimal.ONE.subtract(f));
    }
    if (raw.signum() <= 0) {
      return null;
    }
    return buy ? Rounding.TICK_UP.apply(raw) : Rounding.TICK_DOWN.apply(raw);
  }

  private static boolean crosses(boolean buy, BigDecimal price, BigDecimal stop) {
    if (stop == null || price == null) {
      return false;
    }
    return buy ? price.compareTo(stop) >= 0 : price.compareTo(stop) <= 0;
  }

  private static boolean twoSided(ExecQuote q) {
    return q != null && q.bid() != null && q.ask() != null && q.bid().signum() > 0 && q.ask().signum() > 0;
  }

  // ------------------------------------------------------------------------------------------- engine

  /** The trigger state of a newly indexed stop order (acceptance or boot), from its persisted trailing state. */
  static State stateFor(OrderRow r, Instant trackedAt) {
    return new State(trackedAt, r.watermarkPrice(), r.type() == OrderType.TRAILING_STOP ? r.stopPrice() : null);
  }

  /**
   * Evaluates an armed stop inside its order's engine transaction. Writes the trailing watermark, or the trigger with
   * its event and new latency window; never fills (the fill comes at a later evaluation, once the new
   * {@code eligible_at} passes).
   *
   * @param opening the first evaluation of a session for this order (§12.7 opening gap)
   */
  Result evaluate(OpenOrderIndex.Entry e, OrderRow r, Instant now, List<Print> prints, boolean opening,
                  EngineTx tx) {
    State st = e.trigger;
    if (st == null) {
      State created = stateFor(r, now);
      st = created;
      tx.afterCommit(() -> {
        if (e.trigger == null) {
          e.trigger = created;
        }
      });
    }
    Instant notBefore = st.trackedAt.isAfter(r.eligibleAt()) ? st.trackedAt : r.eligibleAt();
    List<BigDecimal> eligible = new ArrayList<>();
    for (Print p : prints) {
      if (!p.consolidated() || p.observedAt() == null) {
        continue;
      }
      noteConsolidated(r.symbol(), p.observedAt());
      if (!p.observedAt().isBefore(notBefore) && p.price() != null && p.price().signum() > 0) {
        eligible.add(scale4(p.price()));     // sub-penny prints happen; prices are stored at scale 4 (§6.2)
      }
    }
    Instant last = lastConsolidated.get(r.symbol());
    boolean quoteMode = last == null || now.isAfter(last.plus(PRINT_FALLBACK));
    boolean trailing = r.type() == OrderType.TRAILING_STOP;
    boolean needQuote = quoteMode || opening || (trailing && st.watermark == null && eligible.isEmpty());
    ExecQuote quote = null;
    if (needQuote) {
      try {
        quote = marketData.executableQuote(r.symbol(), notBefore, SessionType.REGULAR).orElse(null);
      } catch (RuntimeException ignored) {
        quote = null;            // market data unavailable: prints may still trigger
      }
    }
    Params params = new Params(r.side(), r.type(), r.stopPrice(), r.trailAmount(), r.trailPercent());
    Outcome out = decide(params, trailing ? st.watermark : null, trailing ? st.stop : null, eligible, quote,
        quoteMode, opening);

    if (out.triggered()) {
      trigger(e, st, r, out, now, tx);
      return new Result(true, false);
    }
    if (r.waitReason() != WaitReason.NOT_TRIGGERED) {
      writer.setWaitReason(r.id(), WaitReason.NOT_TRIGGERED);
    }
    if (trailing && out.watermark() != null) {
      trail(st, r, out, now, tx);
    }
    return new Result(false, needQuote && quote == null);
  }

  private void trigger(OpenOrderIndex.Entry e, State st, OrderRow r, Outcome out, Instant now, EngineTx tx) {
    Instant eligibleAt = now.plus(latency.latency(r.publicId() + ":trigger", orders.resumeCount(r.id())));
    writer.trigger(r.id(), now, eligibleAt);
    boolean trailing = r.type() == OrderType.TRAILING_STOP;
    if (trailing) {
      writer.setTrailing(r.id(), out.watermark(), out.stop());
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("price", scale4(out.price()));
    detail.put("basis", out.basis().name());
    detail.put("stopPrice", scale4(out.stop()));
    if (trailing) {
      detail.put("watermark", scale4(out.watermark()));
    }
    detail.put("eligibleAt", eligibleAt.toString());
    writer.event(r.id(), OrderEventType.TRIGGERED, r.status(), now, json.writeValueAsString(detail));
    tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.TRIGGERED, r.status(), now));
    tx.afterCommit(() -> {
      st.watermark = out.watermark();
      st.stop = out.stop();
      st.persistedWatermark = out.watermark();
      st.persistedStop = out.stop();
      st.persistedAt = now;
      e.eligibleAt = eligibleAt;
      e.waitReason = WaitReason.LATENCY;
      e.reopenPending = true;      // STOP_LIMIT: a limit with arriving semantics (§12.7)
      downgradeLease(e);
    });
  }

  /**
   * A triggered stop works like a market or resting order: its lease drops to {@code ORDER_RESTING} (§12.2, §15.5). A
   * repeat lease for the same owner updates the priority.
   */
  private void downgradeLease(OpenOrderIndex.Entry e) {
    try {
      e.lease = marketData.lease(e.symbol, LeasePriority.ORDER_RESTING, "order:" + e.publicId);
    } catch (RuntimeException ignored) {
      // best effort: evaluation requests refreshes when the symbol is not streamed
    }
  }

  /** Persists a moved watermark (≤ 1/s) and writes {@code TRAIL_UPDATED} (≤ 1 per 10 s). */
  private void trail(State st, OrderRow r, Outcome out, Instant now, EngineTx tx) {
    boolean dirty = !same(out.watermark(), st.persistedWatermark) || !same(out.stop(), st.persistedStop);
    boolean emit = !same(out.stop(), st.eventStop)
        && (st.trailEventAt == null || !now.isBefore(st.trailEventAt.plus(TRAIL_EVENT_EVERY)));
    boolean persist = emit || (dirty && (st.persistedAt == null || !now.isBefore(st.persistedAt.plus(PERSIST_EVERY))));
    if (persist) {
      writer.setTrailing(r.id(), out.watermark(), out.stop());
    }
    if (emit) {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("watermark", scale4(out.watermark()));
      detail.put("stopPrice", scale4(out.stop()));
      writer.event(r.id(), OrderEventType.TRAIL_UPDATED, r.status(), now, json.writeValueAsString(detail));
      tx.publish(new OrderChanged(r.accountId(), r.playerId(), r.publicId(), OrderEventType.TRAIL_UPDATED,
          r.status(), now));
    }
    tx.afterCommit(() -> {
      st.watermark = out.watermark();
      st.stop = out.stop();
      if (persist) {
        st.persistedWatermark = out.watermark();
        st.persistedStop = out.stop();
        st.persistedAt = now;
      }
      if (emit) {
        st.eventStop = out.stop();
        st.trailEventAt = now;
      }
    });
  }

  /** The latest consolidated print seen for a symbol (market observation, not order state: kept outside the tx). */
  private void noteConsolidated(String symbol, Instant observedAt) {
    lastConsolidated.merge(symbol, observedAt, (a, b) -> a.isAfter(b) ? a : b);
  }

  /** Forgets what was seen (a simulated restart, §26 Recovery). */
  void clear() {
    lastConsolidated.clear();
  }

  private static BigDecimal scale4(BigDecimal price) {
    return price == null ? null : price.setScale(Prices.SCALE, RoundingMode.HALF_EVEN);
  }

  private static boolean same(BigDecimal a, BigDecimal b) {
    return a == null ? b == null : b != null && a.compareTo(b) == 0;
  }
}
