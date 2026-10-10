package com.stocks.push;

import com.stocks.common.domain.PushCategory;
import com.stocks.common.jdbc.Jdbc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code push_subscription} and {@code push_preference} (§8.5, §19.2); the only writer of both. A subscription is
 * unique by its endpoint ({@code endpoint_hash}), so it belongs to exactly one player: registering it again from
 * another player's session moves it. Endpoints and keys are never logged and never leave the server. Upserts are
 * cast-everything {@code MERGE}s (§8), tested on PostgreSQL ({@code -Ppg-it}).
 */
@Repository
public class PushSubscriptions {

  /** One browser subscription. */
  public record Subscription(long id, String publicId, long playerId, String endpoint, String p256dh,
                             String authSecret, String sessionHash, String label, int failureCount, Instant createdAt,
                             Instant updatedAt, Instant lastSuccessAt, Instant lastFailureAt) {}

  static final String COLUMNS = "id, public_id, player_id, endpoint, p256dh, auth_secret, session_hash, device_label, "
      + "failure_count, created_at, updated_at, last_success_at, last_failure_at";

  private final JdbcClient jdbc;

  public PushSubscriptions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Lowercase hex SHA-256 of a string (endpoints, session ids): what the tables store and match on. */
  public static String sha256(String value) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------------------------------- subscriptions

  /**
   * Registers a subscription for {@code playerId}: inserts it, or (same endpoint) re-binds it to this player, keys,
   * session and label with its failure count reset. Returns the row.
   */
  public Subscription upsert(String publicId, long playerId, String endpoint, String p256dh, String authSecret,
                             String sessionHashOrNull, String label, Instant now) {
    String hash = sha256(endpoint);
    jdbc.sql("""
            MERGE INTO push_subscription t
            USING (VALUES (CAST(? AS VARCHAR(32)), CAST(? AS BIGINT), CAST(? AS VARCHAR(1024)), CAST(? AS VARCHAR(64)),
                           CAST(? AS VARCHAR(100)), CAST(? AS VARCHAR(40)), CAST(? AS VARCHAR(64)),
                           CAST(? AS VARCHAR(80)), CAST(? AS TIMESTAMP WITH TIME ZONE)))
              AS v(public_id, player_id, endpoint, endpoint_hash, p256dh, auth_secret, session_hash, device_label, ts)
            ON t.endpoint_hash = v.endpoint_hash
            WHEN MATCHED THEN UPDATE SET player_id = v.player_id, endpoint = v.endpoint, p256dh = v.p256dh,
              auth_secret = v.auth_secret, session_hash = v.session_hash, device_label = v.device_label,
              failure_count = 0, updated_at = v.ts
            WHEN NOT MATCHED THEN INSERT (public_id, player_id, endpoint, endpoint_hash, p256dh, auth_secret,
              session_hash, device_label, failure_count, created_at, updated_at, last_success_at, last_failure_at)
              VALUES (v.public_id, v.player_id, v.endpoint, v.endpoint_hash, v.p256dh, v.auth_secret, v.session_hash,
                      v.device_label, 0, v.ts, v.ts, NULL, NULL)""")
        .params(publicId, playerId, endpoint, hash, p256dh, authSecret, sessionHashOrNull, label, Jdbc.ts(now))
        .update();
    return byEndpoint(endpoint).orElseThrow();
  }

  public Optional<Subscription> byEndpoint(String endpoint) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscription WHERE endpoint_hash = ?").param(sha256(endpoint))
        .query(this::row).optional();
  }

  public Optional<Subscription> byId(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscription WHERE id = ?").param(id).query(this::row)
        .optional();
  }

  /** The player's devices, oldest first. */
  public List<Subscription> forPlayer(long playerId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM push_subscription WHERE player_id = ? ORDER BY created_at, id")
        .param(playerId).query(this::row).list();
  }

  /** Re-binds a subscription to the HTTP session that just confirmed it (sync after a restart or re-login). */
  public void bindSession(long id, String sessionHashOrNull, Instant now) {
    jdbc.sql("UPDATE push_subscription SET session_hash = :session, updated_at = :now WHERE id = :id")
        .param("session", new SqlParameterValue(Types.VARCHAR, sessionHashOrNull))
        .param("now", Jdbc.ts(now)).param("id", id).update();
  }

  /** A login or password change rotated the session id: the devices it registered follow. */
  public int rebindSession(String oldHash, String newHash) {
    return jdbc.sql("UPDATE push_subscription SET session_hash = ? WHERE session_hash = ?").params(newHash, oldHash)
        .update();
  }

  public int delete(long id) {
    return jdbc.sql("DELETE FROM push_subscription WHERE id = ?").param(id).update();
  }

  public int delete(long playerId, String publicId) {
    return jdbc.sql("DELETE FROM push_subscription WHERE player_id = ? AND public_id = ?").params(playerId, publicId)
        .update();
  }

  public int deleteEndpoint(long playerId, String endpoint) {
    return jdbc.sql("DELETE FROM push_subscription WHERE player_id = ? AND endpoint_hash = ?")
        .params(playerId, sha256(endpoint)).update();
  }

  /** Logout: the devices registered by that session. */
  public int deleteBySession(String sessionHash) {
    return jdbc.sql("DELETE FROM push_subscription WHERE session_hash = ?").param(sessionHash).update();
  }

  /** Password change, freeze, disable, role change: every device of the player. */
  public int deleteByPlayerPublicId(String playerPublicId) {
    return jdbc.sql("""
            DELETE FROM push_subscription WHERE player_id IN (SELECT id FROM player WHERE public_id = ?)""")
        .param(playerPublicId).update();
  }

  public int deleteByPlayer(long playerId) {
    return jdbc.sql("DELETE FROM push_subscription WHERE player_id = ?").param(playerId).update();
  }

  /** Keeps the player's {@code keep} most recently registered devices; returns how many went. */
  public int trim(long playerId, int keep) {
    List<Long> ids = jdbc.sql("SELECT id FROM push_subscription WHERE player_id = ? ORDER BY updated_at DESC, id DESC")
        .param(playerId).query(Long.class).list();
    if (ids.size() <= keep) {
      return 0;
    }
    return jdbc.sql("DELETE FROM push_subscription WHERE id IN (:ids)").param("ids", ids.subList(keep, ids.size()))
        .update();
  }

  public void recordSuccess(long id, Instant now) {
    jdbc.sql("UPDATE push_subscription SET failure_count = 0, last_success_at = ? WHERE id = ?")
        .params(Jdbc.ts(now), id).update();
  }

  /** One more consecutive failure; returns the new count (0 when the row is gone). */
  public int recordFailure(long id, Instant now) {
    jdbc.sql("UPDATE push_subscription SET failure_count = failure_count + 1, last_failure_at = ? WHERE id = ?")
        .params(Jdbc.ts(now), id).update();
    return jdbc.sql("SELECT failure_count FROM push_subscription WHERE id = ?").param(id).query(Integer.class)
        .optional().orElse(0);
  }

  /** Players with at least one device who did not switch {@code category} off. */
  public List<Long> playersWanting(PushCategory category) {
    return jdbc.sql("""
            SELECT DISTINCT s.player_id FROM push_subscription s
             WHERE NOT EXISTS (SELECT 1 FROM push_preference p
                                WHERE p.player_id = s.player_id AND p.category = ? AND p.enabled = FALSE)
             ORDER BY s.player_id""")
        .param(category.name()).query(Long.class).list();
  }

  // ------------------------------------------------------------------------------------------- preferences

  /** Every category, default on; only changed categories have rows. */
  public Map<PushCategory, Boolean> preferences(long playerId) {
    Map<PushCategory, Boolean> out = new EnumMap<>(PushCategory.class);
    for (PushCategory c : PushCategory.values()) {
      out.put(c, true);
    }
    jdbc.sql("SELECT category, enabled FROM push_preference WHERE player_id = ?").param(playerId)
        .query((rs, n) -> {
          try {
            out.put(PushCategory.valueOf(rs.getString("category")), rs.getBoolean("enabled"));
          } catch (IllegalArgumentException ignored) {
            // a category this version no longer knows
          }
          return null;
        }).list();
    return out;
  }

  public boolean wants(long playerId, PushCategory category) {
    return jdbc.sql("SELECT enabled FROM push_preference WHERE player_id = ? AND category = ?")
        .params(playerId, category.name()).query(Boolean.class).optional().orElse(true);
  }

  public void setPreference(long playerId, PushCategory category, boolean enabled, Instant now) {
    jdbc.sql("""
            MERGE INTO push_preference t
            USING (VALUES (CAST(? AS BIGINT), CAST(? AS VARCHAR(16)), CAST(? AS BOOLEAN),
                           CAST(? AS TIMESTAMP WITH TIME ZONE))) AS v(player_id, category, enabled, ts)
            ON t.player_id = v.player_id AND t.category = v.category
            WHEN MATCHED THEN UPDATE SET enabled = v.enabled, updated_at = v.ts
            WHEN NOT MATCHED THEN INSERT (player_id, category, enabled, updated_at)
              VALUES (v.player_id, v.category, v.enabled, v.ts)""")
        .params(playerId, category.name(), enabled, Jdbc.ts(now)).update();
  }

  // ------------------------------------------------------------------------------------------- rows

  private Subscription row(ResultSet rs, int n) throws SQLException {
    return new Subscription(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"),
        rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth_secret"), rs.getString("session_hash"),
        rs.getString("device_label"), rs.getInt("failure_count"), Jdbc.instant(rs, "created_at"),
        Jdbc.instant(rs, "updated_at"), Jdbc.instant(rs, "last_success_at"), Jdbc.instant(rs, "last_failure_at"));
  }
}
