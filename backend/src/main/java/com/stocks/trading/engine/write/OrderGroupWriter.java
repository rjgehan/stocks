package com.stocks.trading.engine.write;

import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.trading.engine.EngineThread;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/** The only writer of {@code order_group} (§11.9); engine thread only. */
@Component
public class OrderGroupWriter {

  private final JdbcClient jdbc;

  public OrderGroupWriter(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A new group; returns its id. */
  public long insert(String publicId, long accountId, long playerId, String clientOrderId, String requestHash,
                     OrderGroupType type, String symbol, BigDecimal qty, TimeInForce timeInForce, Instant now) {
    EngineThread.assertCurrent();
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.sql("""
            INSERT INTO order_group (public_id, account_id, player_id, client_order_id, request_hash, group_type,
                                     symbol, qty, time_in_force, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId, accountId, playerId, clientOrderId, requestHash, type.name(), symbol, qty,
            timeInForce.name(), Jdbc.ts(now), Jdbc.ts(now))
        .update(keys, "id");
    return keys.getKeyAs(Long.class);
  }

  /** Records a group {@code clientOrderId} ({@code PLACE} or {@code REPLACE}) for idempotency (§11.9). */
  public void request(long accountId, long groupId, String clientOrderId, String requestHash, String kind,
                      Instant now) {
    EngineThread.assertCurrent();
    jdbc.sql("INSERT INTO order_group_request (account_id, group_id, client_order_id, request_hash, kind, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(accountId, groupId, clientOrderId, requestHash, kind, Jdbc.ts(now)).update();
  }

  /** A group replace (§11.9): the new group {@code clientOrderId} and hash, quantity and time in force. */
  public void replaced(long groupId, String clientOrderId, String requestHash, BigDecimal qty, TimeInForce tif,
                       Instant now) {
    EngineThread.assertCurrent();
    jdbc.sql("""
            UPDATE order_group SET client_order_id = ?, request_hash = ?, qty = ?, time_in_force = ?, updated_at = ?,
                   version = version + 1
             WHERE id = ?""")
        .params(clientOrderId, requestHash, qty, tif.name(), Jdbc.ts(now), groupId).update();
  }

  /** Any change to a leg ({@code updated_at}). */
  public void touch(long groupId, Instant now) {
    EngineThread.assertCurrent();
    jdbc.sql("UPDATE order_group SET updated_at = ?, version = version + 1 WHERE id = ?")
        .params(Jdbc.ts(now), groupId).update();
  }
}
