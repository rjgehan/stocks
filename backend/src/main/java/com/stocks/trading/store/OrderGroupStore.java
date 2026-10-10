package com.stocks.trading.store;

import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.jdbc.Jdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read access to {@code order_group} (§11.9). Its legs are {@code trade_order} rows ({@link OrderStore#legs}). Reads go
 * through the caller's transaction when there is one, like {@link OrderStore}.
 */
@Component
public class OrderGroupStore {

  private static final String SELECT = """
      SELECT g.id, g.public_id, g.account_id, a.public_id AS account_public_id, g.player_id, g.client_order_id,
             g.request_hash, g.group_type, g.symbol, g.qty, g.time_in_force, g.created_at, g.updated_at
        FROM order_group g
        JOIN account a ON a.id = g.account_id
      """;

  private final JdbcClient jdbc;

  public OrderGroupStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<OrderGroupRow> byId(long id) {
    return jdbc.sql(SELECT + " WHERE g.id = ?").param(id).query(OrderGroupStore::row).optional();
  }

  public Optional<OrderGroupRow> byPublicId(long accountId, String publicId) {
    return jdbc.sql(SELECT + " WHERE g.account_id = ? AND g.public_id = ?").params(accountId, publicId)
        .query(OrderGroupStore::row).optional();
  }

  /** The group whose own {@code clientOrderId} (its idempotency key, §11.9) this is. */
  public Optional<OrderGroupRow> byClientOrderId(long accountId, String clientOrderId) {
    return jdbc.sql(SELECT + " WHERE g.account_id = ? AND g.client_order_id = ?").params(accountId, clientOrderId)
        .query(OrderGroupStore::row).optional();
  }

  /** A group {@code clientOrderId} ever used by this account (a placement's or a group replace's, §11.9). */
  public record Request(long groupId, String clientOrderId, String requestHash, String kind) {}

  public Optional<Request> request(long accountId, String clientOrderId) {
    return jdbc.sql("SELECT group_id, client_order_id, request_hash, kind FROM order_group_request"
            + " WHERE account_id = ? AND client_order_id = ?")
        .params(accountId, clientOrderId)
        .query((rs, n) -> new Request(rs.getLong("group_id"), rs.getString("client_order_id"),
            rs.getString("request_hash"), rs.getString("kind")))
        .optional();
  }

  static OrderGroupRow row(ResultSet rs, int n) throws SQLException {
    return new OrderGroupRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("account_id"),
        rs.getString("account_public_id"), rs.getLong("player_id"), rs.getString("client_order_id"),
        rs.getString("request_hash"), OrderGroupType.valueOf(rs.getString("group_type")), rs.getString("symbol"),
        rs.getBigDecimal("qty"), TimeInForce.valueOf(rs.getString("time_in_force")),
        Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"));
  }
}
