package com.stocks.agents;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.agents.dto.RunRequestStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code agent_run_request} (§20.9). Written only by {@link RunRequestService}. {@code open_bot_id} is the bot's id
 * while a request is PENDING or CLAIMED and NULL once it is final, so its unique constraint keeps one open request per
 * bot; every status change here sets it accordingly.
 */
@Repository
public class RunRequestRepository {

  /** One row, with the handles of the bot and of the human who asked. */
  public record RequestRow(long id, String publicId, long playerId, String handle, long requestedById,
                           String requestedBy, Instant requestedAt, boolean dryRun, RunRequestStatus status,
                           Instant claimedAt, Long claimedKeyId, Instant finishedAt, String message) {}

  /** The requests of a bot made after {@code since}: how many, and when the oldest of them was made. */
  public record Made(int count, Instant oldest) {}

  private static final String SELECT = """
      SELECT r.id, r.public_id, r.player_id, b.handle AS bot_handle, r.requested_by, h.handle AS by_handle,
             r.requested_at, r.dry_run, r.status, r.claimed_at, r.claimed_key_id, r.finished_at, r.message
        FROM agent_run_request r JOIN player b ON b.id = r.player_id JOIN player h ON h.id = r.requested_by
      """;

  private final JdbcClient jdbc;

  public RunRequestRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A new PENDING request; a second open request of the bot fails with {@code DuplicateKeyException}. */
  public void insert(String publicId, long playerId, long requestedBy, Instant at, boolean dryRun) {
    jdbc.sql("""
            INSERT INTO agent_run_request (public_id, player_id, requested_by, requested_at, dry_run, status,
                                           open_bot_id)
            VALUES (?, ?, ?, ?, ?, 'PENDING', ?)""")
        .params(publicId, playerId, requestedBy, Jdbc.ts(at), dryRun, playerId)
        .update();
  }

  public Optional<RequestRow> byPublicId(String publicId) {
    return jdbc.sql(SELECT + " WHERE r.public_id = ?").param(publicId).query(RunRequestRepository::row).optional();
  }

  /** The bot's open (PENDING or CLAIMED) request, if any. */
  public Optional<RequestRow> open(long playerId) {
    return jdbc.sql(SELECT + " WHERE r.open_bot_id = ?").param(playerId).query(RunRequestRepository::row).optional();
  }

  /** Newest first; {@code limit} rows at most. */
  public List<RequestRow> latest(long playerId, int limit) {
    return jdbc.sql(SELECT + " WHERE r.player_id = ? ORDER BY r.requested_at DESC, r.id DESC LIMIT "
        + Math.max(1, limit)).param(playerId).query(RunRequestRepository::row).list();
  }

  /** The bot's oldest PENDING request (there is at most one while the open-request constraint holds). */
  public Optional<RequestRow> oldestPending(long playerId) {
    return jdbc.sql(SELECT + " WHERE r.player_id = ? AND r.status = 'PENDING' ORDER BY r.requested_at, r.id LIMIT 1")
        .param(playerId).query(RunRequestRepository::row).optional();
  }

  public Made madeSince(long playerId, Instant since) {
    return jdbc.sql("SELECT COUNT(*) AS n, MIN(requested_at) AS oldest FROM agent_run_request WHERE player_id = ? "
            + "AND requested_at > ?")
        .params(playerId, Jdbc.ts(since))
        .query((rs, n) -> new Made(rs.getInt("n"), Jdbc.instant(rs, "oldest")))
        .single();
  }

  /** PENDING → CLAIMED, only if it is still PENDING: 1 when this call claimed it, 0 when someone else was first. */
  public int claim(long id, Instant at, Long keyId) {
    return jdbc.sql("UPDATE agent_run_request SET status = 'CLAIMED', claimed_at = ?, claimed_key_id = ? "
            + "WHERE id = ? AND status = 'PENDING'")
        .params(Jdbc.ts(at), new SqlParameterValue(Types.BIGINT, keyId), id)
        .update();
  }

  /** CLAIMED → {@code status} (DONE or FAILED), only if it is still CLAIMED: the number of rows changed. */
  public int finish(long id, RunRequestStatus status, Instant at, String message) {
    return jdbc.sql("UPDATE agent_run_request SET status = ?, finished_at = ?, message = ?, open_bot_id = NULL "
            + "WHERE id = ? AND status = 'CLAIMED'")
        .params(status.name(), Jdbc.ts(at), new SqlParameterValue(Types.VARCHAR, message), id)
        .update();
  }

  /** The bot's PENDING requests made before {@code before} become EXPIRED with {@code message}. */
  public int expirePending(long playerId, Instant before, Instant at, String message) {
    return jdbc.sql("UPDATE agent_run_request SET status = 'EXPIRED', finished_at = ?, message = ?, open_bot_id = NULL "
            + "WHERE player_id = ? AND status = 'PENDING' AND requested_at < ?")
        .params(Jdbc.ts(at), message, playerId, Jdbc.ts(before))
        .update();
  }

  /** Every PENDING request of the bot becomes EXPIRED with {@code message} (the bot cannot be run now). */
  public int expireAllPending(long playerId, Instant at, String message) {
    return jdbc.sql("UPDATE agent_run_request SET status = 'EXPIRED', finished_at = ?, message = ?, open_bot_id = NULL "
            + "WHERE player_id = ? AND status = 'PENDING'")
        .params(Jdbc.ts(at), message, playerId)
        .update();
  }

  /** The bot's CLAIMED requests claimed before {@code before} become FAILED with {@code message}. */
  public int failStaleClaims(long playerId, Instant before, Instant at, String message) {
    return jdbc.sql("UPDATE agent_run_request SET status = 'FAILED', finished_at = ?, message = ?, open_bot_id = NULL "
            + "WHERE player_id = ? AND status = 'CLAIMED' AND claimed_at < ?")
        .params(Jdbc.ts(at), message, playerId, Jdbc.ts(before))
        .update();
  }

  private static RequestRow row(ResultSet rs, int n) throws SQLException {
    return new RequestRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"),
        rs.getString("bot_handle"), rs.getLong("requested_by"), rs.getString("by_handle"),
        Jdbc.instant(rs, "requested_at"), rs.getBoolean("dry_run"), RunRequestStatus.valueOf(rs.getString("status")),
        Jdbc.instant(rs, "claimed_at"), Jdbc.longOrNull(rs, "claimed_key_id"), Jdbc.instant(rs, "finished_at"),
        rs.getString("message"));
  }
}
