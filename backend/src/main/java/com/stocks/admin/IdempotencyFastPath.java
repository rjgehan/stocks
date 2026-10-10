package com.stocks.admin;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.IdempotencyKeys;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step 1 of §17.6 for the admin writes that carry an {@code Idempotency-Key} (cash transfers, resets): a read-only
 * lookup of {@code idempotency_record(scope, key)} on the request thread. A stored response with the same request hash
 * is replayed with {@code Idempotent-Replay: true} without enqueuing anything; a different hash is 409
 * {@code IDEMPOTENCY_KEY_CONFLICT}. Everything else goes to the engine, whose in-transaction check (step 2) is
 * authoritative. The row itself is written by WS3's engine command, never here.
 */
@Component
public class IdempotencyFastPath {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyFastPath.class);

  record Stored(String requestHash, int statusCode, String body) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final JsonMapper jsonMapper;

  public IdempotencyFastPath(JdbcClient jdbc, TimeSource time, JsonMapper jsonMapper) {
    this.jdbc = jdbc;
    this.time = time;
    this.jsonMapper = jsonMapper;
  }

  /**
   * The stored response to replay, if any.
   *
   * @throws ApiException 409 {@code IDEMPOTENCY_KEY_CONFLICT} when the key was used with another request
   */
  public <T> Optional<ResponseEntity<T>> replay(String scope, String key, String requestHash, Class<T> type) {
    Optional<Stored> stored = jdbc.sql("""
            SELECT request_hash, status_code, response_body FROM idempotency_record
             WHERE scope = ? AND idem_key = ? AND expires_at > ?""")
        .params(scope, key, Jdbc.ts(time.now()))
        .query((rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
        .optional();
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    if (!stored.get().requestHash().equals(requestHash)) {
      throw conflict();
    }
    try {
      T body = jsonMapper.readValue(stored.get().body(), type);
      return Optional.of(ResponseEntity.status(HttpStatus.valueOf(stored.get().statusCode()))
          .header(IdempotencyKeys.REPLAY_HEADER, "true").body(body));
    } catch (JacksonException | IllegalArgumentException e) {
      log.warn("Stored idempotent response {}:{} is not a {}; asking the engine instead", scope, key,
          type.getSimpleName());
      return Optional.empty();
    }
  }

  static ApiException conflict() {
    return ApiException.of(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "This Idempotency-Key was already used with a different request.")
        .withHint("Use a new Idempotency-Key for a new request; reuse a key only to retry the same request.");
  }
}
