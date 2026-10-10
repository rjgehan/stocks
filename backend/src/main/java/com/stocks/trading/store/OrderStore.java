package com.stocks.trading.store;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Qty;
import com.stocks.common.web.Cursor;
import com.stocks.contract.trading.ExecutionFilter;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderStatusFilter;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read access to {@code trade_order}, {@code order_event} and {@code execution} (§8.1). Reads go through the
 * caller's transaction when there is one (JdbcClient is transaction-aware), so the engine sees its own uncommitted
 * writes; REST reads see committed state (§4 rule 4).
 */
@Component
public class OrderStore {

  static final String OPEN_STATUSES = "('PENDING','WORKING','PARTIALLY_FILLED')";

  private static final String SELECT_ORDER = """
      SELECT o.id, o.public_id, o.account_id, a.public_id AS account_public_id, o.player_id, o.client_order_id,
             o.request_hash, o.symbol, o.side, o.order_type, o.qty, o.notional, o.fractional, o.limit_price,
             o.stop_price, o.trail_amount, o.trail_percent, o.watermark_price, o.collar_price, o.time_in_force,
             o.extended_hours, o.status, o.wait_reason, o.status_reason, o.triggered, o.has_rested, o.filled_qty,
             o.filled_principal, o.avg_fill_price, o.fees_total, o.reserved_cash, o.reserved_qty, o.session_date,
             o.received_at, o.accepted_at, o.eligible_at, o.activated_at, o.triggered_at, o.last_fill_at,
             o.expires_at, o.done_at, r1.public_id AS replaces_public_id, r2.public_id AS replaced_by_public_id,
             o.channel, o.api_key_id, o.rationale, o.rationale_public, o.group_id, g.public_id AS group_public_id,
             g.group_type, o.group_role, o.parent_order_id, p.public_id AS parent_public_id, o.held, o.do_not_reduce,
             o.cancel_requested_at, o.cancel_effective_at
        FROM trade_order o
        JOIN account a ON a.id = o.account_id
        LEFT JOIN trade_order r1 ON r1.id = o.replaces_order_id
        LEFT JOIN trade_order r2 ON r2.id = o.replaced_by_order_id
        LEFT JOIN order_group g ON g.id = o.group_id
        LEFT JOIN trade_order p ON p.id = o.parent_order_id
      """;

  private static final String SELECT_EXECUTION = """
      SELECT e.id, e.public_id, e.order_id, o.public_id AS order_public_id, e.account_id, e.symbol, e.side, e.qty,
             e.price, e.principal, e.fee_sec, e.fee_taf, e.fee_cat, e.net_amount, e.realized_pnl, e.liquidity,
             e.executed_at, e.trade_date, e.settle_date, e.ref_bid, e.ref_ask, e.ref_bid_size, e.ref_ask_size,
             e.ref_last, e.ref_quote_ts, e.ref_confirmed_at, e.ref_source, e.ref_quality, e.ref_clamped
        FROM execution e
        JOIN trade_order o ON o.id = e.order_id
      """;

  private final JdbcClient jdbc;

  public OrderStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- orders

  public Optional<OrderRow> byId(long id) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.id = ?").param(id).query(OrderStore::order).optional();
  }

  public Optional<OrderRow> byPublicId(String publicId) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.public_id = ?").param(publicId).query(OrderStore::order).optional();
  }

  public Optional<OrderRow> byPublicId(long accountId, String publicId) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.account_id = ? AND o.public_id = ?").params(accountId, publicId)
        .query(OrderStore::order).optional();
  }

  public Optional<OrderRow> byClientOrderId(long accountId, String clientOrderId) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.account_id = ? AND o.client_order_id = ?")
        .params(accountId, clientOrderId).query(OrderStore::order).optional();
  }

  /** Every open order, oldest first (engine bootstrap). */
  public List<OrderRow> open() {
    return jdbc.sql(SELECT_ORDER + " WHERE o.status IN " + OPEN_STATUSES + " ORDER BY o.eligible_at, o.id")
        .query(OrderStore::order).list();
  }

  /** The legs of an order group, oldest first, REPLACED ones included (§11.9). */
  public List<OrderRow> legs(long groupId) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.group_id = ? ORDER BY o.id").param(groupId)
        .query(OrderStore::order).list();
  }

  /** Open orders of an account, optionally on one symbol, oldest first. */
  public List<OrderRow> openByAccount(long accountId, String symbolOrNull) {
    if (symbolOrNull == null) {
      return jdbc.sql(SELECT_ORDER + " WHERE o.account_id = ? AND o.status IN " + OPEN_STATUSES
          + " ORDER BY o.received_at, o.id").param(accountId).query(OrderStore::order).list();
    }
    return jdbc.sql(SELECT_ORDER + " WHERE o.account_id = ? AND o.symbol = ? AND o.status IN " + OPEN_STATUSES
        + " ORDER BY o.received_at, o.id").params(accountId, symbolOrNull).query(OrderStore::order).list();
  }

  public List<OrderRow> openBySymbol(String symbol) {
    return jdbc.sql(SELECT_ORDER + " WHERE o.symbol = ? AND o.status IN " + OPEN_STATUSES
        + " ORDER BY o.eligible_at, o.id").param(symbol).query(OrderStore::order).list();
  }

  /** A NEEDS_ADMIN split with {@code ex_date ≤ todayEt} puts the symbol on a corporate-action hold (§14). */
  public boolean onCorporateActionHold(String symbol, LocalDate todayEt) {
    return jdbc.sql("""
            SELECT COUNT(*) FROM corporate_action
             WHERE symbol = ? AND status = 'NEEDS_ADMIN' AND action_type IN ('FORWARD_SPLIT','REVERSE_SPLIT')
               AND ex_date <= ?""")
        .params(symbol, todayEt).query(Integer.class).single() > 0;
  }

  public int countOpen(long accountId) {
    return jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ? AND status IN " + OPEN_STATUSES)
        .param(accountId).query(Integer.class).single();
  }

  /** Σ {@code reserved_cash} of open BUY orders (§10.2 {@code openBuyHolds}). */
  public BigDecimal openBuyHolds(long accountId) {
    return jdbc.sql("SELECT COALESCE(SUM(reserved_cash), 0) FROM trade_order WHERE account_id = ? AND side = 'BUY'"
            + " AND status IN " + OPEN_STATUSES)
        .param(accountId).query(BigDecimal.class).single().setScale(Money.SCALE);
  }

  /** Σ {@code reserved_qty} of open SELL orders on {@code symbol}, excluding one order (or none: -1). */
  public BigDecimal reservedSellQty(long accountId, String symbol, long excludingOrderId) {
    return jdbc.sql("SELECT COALESCE(SUM(reserved_qty), 0) FROM trade_order WHERE account_id = ? AND symbol = ?"
            + " AND side = 'SELL' AND status IN " + OPEN_STATUSES + " AND id <> ?")
        .params(accountId, symbol, excludingOrderId).query(BigDecimal.class).single().setScale(Qty.SCALE);
  }

  /** {@code GET /orders}: newest first by {@code (received_at, id)}; fetches {@code limit + 1} rows. */
  public List<OrderRow> list(long accountId, OrderFilter filter, Cursor cursor, int limitPlusOne) {
    StringBuilder sql = new StringBuilder(SELECT_ORDER).append(" WHERE o.account_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(accountId);
    OrderStatusFilter status = filter == null || filter.status() == null ? OrderStatusFilter.ALL : filter.status();
    if (status == OrderStatusFilter.OPEN) {
      sql.append(" AND o.status IN ").append(OPEN_STATUSES);
    } else if (status == OrderStatusFilter.CLOSED) {
      sql.append(" AND o.status NOT IN ").append(OPEN_STATUSES);
    }
    if (filter != null && filter.symbolOrNull() != null) {
      sql.append(" AND o.symbol = ?");
      params.add(filter.symbolOrNull());
    }
    if (filter != null && filter.fromOrNull() != null) {
      sql.append(" AND o.received_at >= ?");
      params.add(Jdbc.ts(filter.fromOrNull()));
    }
    if (filter != null && filter.toOrNull() != null) {
      sql.append(" AND o.received_at < ?");
      params.add(Jdbc.ts(filter.toOrNull()));
    }
    if (cursor != null) {
      sql.append(" AND (o.received_at < ? OR (o.received_at = ? AND o.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY o.received_at DESC, o.id DESC LIMIT ?");
    params.add(limitPlusOne);
    return jdbc.sql(sql.toString()).params(params).query(OrderStore::order).list();
  }

  // ------------------------------------------------------------------------------------------- events

  public List<OrderEventRow> events(long orderId) {
    return jdbc.sql("SELECT order_id, seq, event_type, status_after, occurred_at, detail FROM order_event"
            + " WHERE order_id = ? ORDER BY seq")
        .param(orderId)
        .query((rs, n) -> new OrderEventRow(rs.getLong("order_id"), rs.getInt("seq"),
            OrderEventType.valueOf(rs.getString("event_type")), OrderStatus.valueOf(rs.getString("status_after")),
            Jdbc.instant(rs, "occurred_at"), rs.getString("detail")))
        .list();
  }

  /** How many times the order has been resumed (its latency {@code activationNo}, §11.3). */
  public int resumeCount(long orderId) {
    return jdbc.sql("SELECT COUNT(*) FROM order_event WHERE order_id = ? AND event_type = 'RESUMED'")
        .param(orderId).query(Integer.class).single();
  }

  // ------------------------------------------------------------------------------------------- executions

  public List<ExecutionRow> executionsOfOrder(long orderId) {
    return jdbc.sql(SELECT_EXECUTION + " WHERE e.order_id = ? ORDER BY e.id").param(orderId)
        .query(OrderStore::execution).list();
  }

  public Optional<ExecutionRow> execution(long id) {
    return jdbc.sql(SELECT_EXECUTION + " WHERE e.id = ?").param(id).query(OrderStore::execution).optional();
  }

  /** Σ qty and Σ principal of an order's executions on one trade date (cumulative fees, §13). */
  public BigDecimal[] sameDaySums(long orderId, LocalDate tradeDate) {
    return jdbc.sql("SELECT COALESCE(SUM(qty), 0) AS q, COALESCE(SUM(principal), 0) AS p FROM execution"
            + " WHERE order_id = ? AND trade_date = ?")
        .params(orderId, tradeDate)
        .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("q"), rs.getBigDecimal("p")})
        .single();
  }

  /** {@code GET /account/executions}: newest first by {@code (executed_at, id)}; fetches {@code limit + 1}. */
  public List<ExecutionRow> executions(long accountId, ExecutionFilter filter, Cursor cursor, int limitPlusOne) {
    StringBuilder sql = new StringBuilder(SELECT_EXECUTION).append(" WHERE e.account_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(accountId);
    if (filter != null && filter.symbolOrNull() != null) {
      sql.append(" AND e.symbol = ?");
      params.add(filter.symbolOrNull());
    }
    if (filter != null && filter.fromOrNull() != null) {
      sql.append(" AND e.executed_at >= ?");
      params.add(Jdbc.ts(filter.fromOrNull()));
    }
    if (filter != null && filter.toOrNull() != null) {
      sql.append(" AND e.executed_at < ?");
      params.add(Jdbc.ts(filter.toOrNull()));
    }
    if (cursor != null) {
      sql.append(" AND (e.executed_at < ? OR (e.executed_at = ? AND e.id < ?))");
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    sql.append(" ORDER BY e.executed_at DESC, e.id DESC LIMIT ?");
    params.add(limitPlusOne);
    return jdbc.sql(sql.toString()).params(params).query(OrderStore::execution).list();
  }

  // ------------------------------------------------------------------------------------------- mapping

  static OrderRow order(ResultSet rs, int n) throws SQLException {
    String wait = rs.getString("wait_reason");
    return new OrderRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("account_id"),
        rs.getString("account_public_id"), rs.getLong("player_id"), rs.getString("client_order_id"),
        rs.getString("request_hash"), rs.getString("symbol"), Side.valueOf(rs.getString("side")),
        OrderType.valueOf(rs.getString("order_type")), rs.getBigDecimal("qty"), rs.getBigDecimal("notional"),
        rs.getBoolean("fractional"), rs.getBigDecimal("limit_price"), rs.getBigDecimal("stop_price"),
        rs.getBigDecimal("trail_amount"), rs.getBigDecimal("trail_percent"), rs.getBigDecimal("watermark_price"),
        rs.getBigDecimal("collar_price"), TimeInForce.valueOf(rs.getString("time_in_force")),
        rs.getBoolean("extended_hours"), OrderStatus.valueOf(rs.getString("status")),
        wait == null ? null : WaitReason.valueOf(wait), rs.getString("status_reason"), rs.getBoolean("triggered"),
        rs.getBoolean("has_rested"), rs.getBigDecimal("filled_qty"), rs.getBigDecimal("filled_principal"),
        rs.getBigDecimal("avg_fill_price"), rs.getBigDecimal("fees_total"), rs.getBigDecimal("reserved_cash"),
        rs.getBigDecimal("reserved_qty"), Jdbc.date(rs, "session_date"), Jdbc.instant(rs, "received_at"),
        Jdbc.instant(rs, "accepted_at"), Jdbc.instant(rs, "eligible_at"), Jdbc.instant(rs, "activated_at"),
        Jdbc.instant(rs, "triggered_at"), Jdbc.instant(rs, "last_fill_at"), Jdbc.instant(rs, "expires_at"),
        Jdbc.instant(rs, "done_at"), rs.getString("replaces_public_id"), rs.getString("replaced_by_public_id"),
        Channel.valueOf(rs.getString("channel")), Jdbc.longOrNull(rs, "api_key_id"), rs.getString("rationale"),
        rs.getBoolean("rationale_public"), Jdbc.longOrNull(rs, "group_id"), rs.getString("group_public_id"),
        enumOrNull(OrderGroupType.class, rs.getString("group_type")),
        enumOrNull(OrderGroupRole.class, rs.getString("group_role")), Jdbc.longOrNull(rs, "parent_order_id"),
        rs.getString("parent_public_id"), rs.getBoolean("held"), rs.getBoolean("do_not_reduce"),
        Jdbc.instant(rs, "cancel_requested_at"), Jdbc.instant(rs, "cancel_effective_at"));
  }

  private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
    return value == null ? null : Enum.valueOf(type, value);
  }

  static ExecutionRow execution(ResultSet rs, int n) throws SQLException {
    return new ExecutionRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("order_id"),
        rs.getString("order_public_id"), rs.getLong("account_id"), rs.getString("symbol"),
        Side.valueOf(rs.getString("side")), rs.getBigDecimal("qty"), rs.getBigDecimal("price"),
        rs.getBigDecimal("principal"), rs.getBigDecimal("fee_sec"), rs.getBigDecimal("fee_taf"),
        rs.getBigDecimal("fee_cat"), rs.getBigDecimal("net_amount"), rs.getBigDecimal("realized_pnl"),
        Liquidity.valueOf(rs.getString("liquidity")), Jdbc.instant(rs, "executed_at"), Jdbc.date(rs, "trade_date"),
        Jdbc.date(rs, "settle_date"), rs.getBigDecimal("ref_bid"), rs.getBigDecimal("ref_ask"),
        Jdbc.longOrNull(rs, "ref_bid_size"), Jdbc.longOrNull(rs, "ref_ask_size"), rs.getBigDecimal("ref_last"),
        Jdbc.instant(rs, "ref_quote_ts"), Jdbc.instant(rs, "ref_confirmed_at"), rs.getString("ref_source"),
        DataQuality.valueOf(rs.getString("ref_quality")), rs.getBoolean("ref_clamped"));
  }
}
