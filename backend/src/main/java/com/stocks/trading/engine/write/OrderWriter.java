package com.stocks.trading.engine.write;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/** The only writer of {@code trade_order} and {@code order_event} (§4 rule 1); engine thread only. */
@Component
public class OrderWriter {

  /**
   * The columns of a new order. {@code stopPrice} is the client's trigger price (STOP, STOP_LIMIT); a trailing stop
   * starts without one (§12.7: it is computed from the watermark at activation).
   */
  public record NewOrder(String publicId, long accountId, long playerId, String clientOrderId, String requestHash,
                         String symbol, Side side, OrderType type, BigDecimal qty, BigDecimal notional,
                         boolean fractional, BigDecimal limitPrice, TimeInForce timeInForce, boolean extendedHours,
                         WaitReason waitReason, BigDecimal reservedCash, BigDecimal reservedQty, LocalDate sessionDate,
                         Instant receivedAt, Instant acceptedAt, Instant eligibleAt, Instant expiresAt,
                         Long replacesOrderId, Channel channel, Long apiKeyId, String oauthClientId, String mcpClient,
                         String clientIp, String rationale, boolean rationalePublic, BigDecimal stopPrice,
                         BigDecimal trailAmount, BigDecimal trailPercent) {

    /** A MARKET or LIMIT order (no stop or trail). */
    public NewOrder(String publicId, long accountId, long playerId, String clientOrderId, String requestHash,
                    String symbol, Side side, OrderType type, BigDecimal qty, BigDecimal notional, boolean fractional,
                    BigDecimal limitPrice, TimeInForce timeInForce, boolean extendedHours, WaitReason waitReason,
                    BigDecimal reservedCash, BigDecimal reservedQty, LocalDate sessionDate, Instant receivedAt,
                    Instant acceptedAt, Instant eligibleAt, Instant expiresAt, Long replacesOrderId, Channel channel,
                    Long apiKeyId, String oauthClientId, String mcpClient, String clientIp, String rationale,
                    boolean rationalePublic) {
      this(publicId, accountId, playerId, clientOrderId, requestHash, symbol, side, type, qty, notional, fractional,
          limitPrice, timeInForce, extendedHours, waitReason, reservedCash, reservedQty, sessionDate, receivedAt,
          acceptedAt, eligibleAt, expiresAt, replacesOrderId, channel, apiKeyId, oauthClientId, mcpClient, clientIp,
          rationale, rationalePublic, null, null, null);
    }
  }

  private final JdbcClient jdbc;

  public OrderWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a {@code PENDING} order; returns its id. */
  public long insert(NewOrder o) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO trade_order (public_id, account_id, player_id, client_order_id, request_hash, symbol, side,
                                     order_type, qty, notional, fractional, limit_price, time_in_force,
                                     extended_hours, status, wait_reason, reserved_cash, reserved_qty, session_date,
                                     received_at, accepted_at, eligible_at, expires_at, replaces_order_id, channel,
                                     api_key_id, oauth_client_id, mcp_client, client_ip, rationale, rationale_public,
                                     stop_price, trail_amount, trail_percent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?)""")
        .params(o.publicId(), o.accountId(), o.playerId(), o.clientOrderId(), o.requestHash(), o.symbol(),
            o.side().name(), o.type().name(), Jdbc.decimalParam(o.qty()), Jdbc.decimalParam(o.notional()),
            o.fractional(), Jdbc.decimalParam(o.limitPrice()), o.timeInForce().name(), o.extendedHours(),
            text(o.waitReason() == null ? null : o.waitReason().name()), o.reservedCash(), o.reservedQty(),
            o.sessionDate(), Jdbc.ts(o.receivedAt()), Jdbc.ts(o.acceptedAt()), Jdbc.ts(o.eligibleAt()),
            Jdbc.ts(o.expiresAt()), longParam(o.replacesOrderId()), o.channel().name(), longParam(o.apiKeyId()),
            text(o.oauthClientId()), text(o.mcpClient()), text(o.clientIp()), text(o.rationale()),
            o.rationalePublic(), Jdbc.decimalParam(o.stopPrice()), Jdbc.decimalParam(o.trailAmount()),
            Jdbc.decimalParam(o.trailPercent()))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** PENDING → WORKING. */
  public void activate(long orderId, Instant at) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET status = 'WORKING', activated_at = ?, wait_reason = NULL,"
            + " version = version + 1 WHERE id = ?")
        .params(Jdbc.ts(at), orderId).update();
  }

  public void setWaitReason(long orderId, WaitReason reason) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET wait_reason = ?, version = version + 1 WHERE id = ?")
        .params(text(reason == null ? null : reason.name()), orderId).update();
  }

  /** A new latency window after a resume (§11.3). */
  public void setEligible(long orderId, Instant eligibleAt, WaitReason reason) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET eligible_at = ?, wait_reason = ?, version = version + 1 WHERE id = ?")
        .params(Jdbc.ts(eligibleAt), text(reason == null ? null : reason.name()), orderId).update();
  }

  /** The market protection band, set once (§12.4). */
  public void setCollar(long orderId, BigDecimal collar) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET collar_price = ?, version = version + 1 WHERE id = ?")
        .params(collar, orderId).update();
  }

  /** A trailing stop's watermark and the stop computed from it (§12.7; written at most once per second). */
  public void setTrailing(long orderId, BigDecimal watermark, BigDecimal stopPrice) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET watermark_price = ?, stop_price = ?, version = version + 1 WHERE id = ?")
        .params(Jdbc.decimalParam(watermark), Jdbc.decimalParam(stopPrice), orderId).update();
  }

  /**
   * A stop triggered (§12.7): {@code triggered}, {@code triggered_at}, and a fresh latency window after which it works
   * as a market (STOP, TRAILING_STOP) or limit (STOP_LIMIT) order.
   */
  public void trigger(long orderId, Instant at, Instant eligibleAt) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE trade_order SET triggered = TRUE, triggered_at = ?, eligible_at = ?, wait_reason = 'LATENCY',
                   version = version + 1
             WHERE id = ?""")
        .params(Jdbc.ts(at), Jdbc.ts(eligibleAt), orderId).update();
  }

  /** The limit order now rests (§12.5). */
  public void setRested(long orderId) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET has_rested = TRUE, version = version + 1 WHERE id = ?").param(orderId).update();
  }

  /** A notional order's reserved quantity, fixed at execution. */
  public void setReservedCash(long orderId, BigDecimal reservedCash) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET reserved_cash = ?, version = version + 1 WHERE id = ?")
        .params(reservedCash, orderId).update();
  }

  /** The order's state after an execution (§12.8 step 6). {@code doneAt} is set when the order is FILLED. */
  public void applyFill(long orderId, BigDecimal filledQty, BigDecimal filledPrincipal, BigDecimal avgFillPrice,
                        BigDecimal feesTotal, OrderStatus status, BigDecimal reservedCash, BigDecimal reservedQty,
                        Instant lastFillAt, Instant doneAtOrNull, WaitReason waitAfter) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE trade_order SET filled_qty = ?, filled_principal = ?, avg_fill_price = ?, fees_total = ?,
                   status = ?, reserved_cash = ?, reserved_qty = ?, last_fill_at = ?, done_at = ?, wait_reason = ?,
                   version = version + 1
             WHERE id = ?""")
        .params(filledQty, filledPrincipal, avgFillPrice, feesTotal, status.name(), reservedCash, reservedQty,
            Jdbc.ts(lastFillAt), Jdbc.tsParam(doneAtOrNull), text(waitAfter == null ? null : waitAfter.name()),
            orderId)
        .update();
  }

  /** A terminal state other than FILLED: releases every hold (CHECK {@code ck_order_terminal_release}). */
  public void finish(long orderId, OrderStatus status, String statusReason, Instant doneAt) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE trade_order SET status = ?, status_reason = ?, done_at = ?, reserved_cash = 0, reserved_qty = 0,
                   wait_reason = NULL, version = version + 1
             WHERE id = ?""")
        .params(status.name(), text(statusReason), Jdbc.ts(doneAt), orderId).update();
  }

  public void linkReplacement(long oldOrderId, long newOrderId) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE trade_order SET replaced_by_order_id = ?, version = version + 1 WHERE id = ?")
        .params(newOrderId, oldOrderId).update();
  }

  /** Appends a timeline event (§11.5); returns its sequence number. */
  public int event(long orderId, OrderEventType type, OrderStatus statusAfter, Instant at, String detailJson) {
    EngineThread.assertCurrent();
    int seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM order_event WHERE order_id = ?").param(orderId)
        .query(Integer.class).single();
    jdbc.sql("INSERT INTO order_event (order_id, seq, event_type, status_after, occurred_at, detail)"
            + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(orderId, seq, type.name(), statusAfter.name(), Jdbc.ts(at), text(detailJson)).update();
    return seq;
  }

  static SqlParameterValue text(String value) {
    return new SqlParameterValue(Types.VARCHAR, value);
  }

  static SqlParameterValue longParam(Long value) {
    return new SqlParameterValue(Types.BIGINT, value);
  }
}
