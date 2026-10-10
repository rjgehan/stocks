package com.stocks.trading.store;

import com.stocks.common.jdbc.Jdbc;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code idempotency_record} for non-order writes (§17.6). The authoritative lookup and the insert run inside the
 * engine command's transaction, so the record exists if and only if the effect does. Rows live 7 days (WS1's
 * housekeeping deletes expired ones); the {@code transfer_batch} / {@code account} backstop keys stay forever.
 */
@Component
public class IdempotencyStore {

  public static final Duration TTL = Duration.ofDays(7);
  /** {@code response_body VARCHAR(20000)}. */
  public static final int MAX_BODY = 20_000;

  /** A stored response. */
  public record Stored(String requestHash, int statusCode, String responseBody) {}

  private final JdbcClient jdbc;

  public IdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Stored> find(String scope, String key) {
    return jdbc.sql("SELECT request_hash, status_code, response_body FROM idempotency_record"
            + " WHERE scope = ? AND idem_key = ?")
        .params(scope, key)
        .query((rs, n) -> new Stored(rs.getString("request_hash"), rs.getInt("status_code"),
            rs.getString("response_body")))
        .optional();
  }

  /** {@code transfer_batch.idempotency_key} backstop (§17.6). */
  public boolean batchKeyUsed(String key) {
    return jdbc.sql("SELECT COUNT(*) FROM transfer_batch WHERE idempotency_key = ?").param(key)
        .query(Integer.class).single() > 0;
  }

  public void insert(String scope, String key, String requestHash, int statusCode, String responseBody, Instant now) {
    jdbc.sql("""
            INSERT INTO idempotency_record (scope, idem_key, request_hash, status_code, response_body, created_at,
                                            expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)""")
        .params(scope, key, requestHash, statusCode, responseBody, Jdbc.ts(now), Jdbc.ts(now.plus(TTL)))
        .update();
  }
}
