package com.stocks.manualbot;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of the manual bots (§20.8): {@code manual_bot} (the owner's instructions) and {@code manual_session} (prompts,
 * replies, parsed rows and results), plus the plain counts of a bot's active API keys and OAuth connectors. The only
 * writer of both tables.
 */
@Repository
public class ManualBotRepository {

  /** A bot's saved instructions and who saved them last. */
  public record BotRow(long playerId, String instructions, String updatedByHandle, Instant updatedAt) {}

  /**
   * A {@code manual_session} row with the bot's and the operator's handles. {@code promptText} and {@code replyText}
   * are null in the list shape (only their lengths are read).
   */
  public record SessionRow(long id, String publicId, long playerId, String botHandle, long accountId,
                           long operatorPlayerId, String operatorHandle, String status, String aiModel,
                           String marketSession, String promptSha256, int promptChars, String promptText,
                           Integer replyChars, String replyText, String replySha256, String summary,
                           String parsedJson, String journalNoteId, Instant createdAt, Instant repliedAt,
                           Instant executedAt) {}

  private static final String SESSION_COLUMNS = """
      s.id, s.public_id, s.player_id, b.handle AS bot_handle, s.account_id, s.operator_player_id,
      o.handle AS operator_handle, s.status, s.ai_model, s.market_session, s.prompt_sha256,
      CHAR_LENGTH(s.prompt_text) AS prompt_chars, CHAR_LENGTH(s.reply_text) AS reply_chars, s.reply_sha256,
      s.summary, s.parsed_json, s.journal_note_id, s.created_at, s.replied_at, s.executed_at""";
  private static final String SESSION_FROM = """
       FROM manual_session s JOIN player b ON b.id = s.player_id JOIN player o ON o.id = s.operator_player_id""";

  private final JdbcClient jdbc;

  public ManualBotRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- manual_bot

  public Optional<BotRow> bot(long playerId) {
    return jdbc.sql("""
            SELECT m.player_id, m.instructions, p.handle AS updated_by, m.updated_at
              FROM manual_bot m JOIN player p ON p.id = m.updated_by
             WHERE m.player_id = ?""")
        .param(playerId)
        .query((rs, n) -> new BotRow(rs.getLong("player_id"), rs.getString("instructions"),
            rs.getString("updated_by"), Jdbc.instant(rs, "updated_at")))
        .optional();
  }

  /** Inserts or replaces the instructions (the §8 cast-everything {@code MERGE}). */
  public void saveInstructions(long playerId, String instructions, long updatedBy, Instant at) {
    jdbc.sql("""
            MERGE INTO manual_bot t
            USING (VALUES (CAST(? AS BIGINT), CAST(? AS VARCHAR(4000)), CAST(? AS BIGINT),
                           CAST(? AS TIMESTAMP WITH TIME ZONE))) AS v(pid, txt, by_id, at)
               ON t.player_id = v.pid
            WHEN MATCHED THEN UPDATE SET instructions = v.txt, updated_by = v.by_id, updated_at = v.at
            WHEN NOT MATCHED THEN INSERT (player_id, instructions, updated_by, updated_at)
                                  VALUES (v.pid, v.txt, v.by_id, v.at)""")
        .params(playerId, instructions, updatedBy, Jdbc.ts(at))
        .update();
  }

  // ------------------------------------------------------------------------------------------- counts

  /** Unrevoked, unexpired API keys of the player. */
  public int activeApiKeys(long playerId, Instant now) {
    return jdbc.sql("SELECT COUNT(*) FROM api_key WHERE player_id = ? AND revoked_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > ?)")
        .params(playerId, Jdbc.ts(now)).query(Integer.class).single();
  }

  /** Unrevoked OAuth grants acting as the player whose code was exchanged (§17.9). */
  public int activeConnectors(long playerId) {
    return jdbc.sql("SELECT COUNT(*) FROM oauth_grant WHERE actor_player_id = ? AND revoked_at IS NULL "
            + "AND code_used_at IS NOT NULL")
        .param(playerId).query(Integer.class).single();
  }

  /** Sessions of the bot created at or after {@code from}. */
  public int sessionsSince(long playerId, Instant from) {
    return jdbc.sql("SELECT COUNT(*) FROM manual_session WHERE player_id = ? AND created_at >= ?")
        .params(playerId, Jdbc.ts(from)).query(Integer.class).single();
  }

  // ------------------------------------------------------------------------------------------- manual_session

  public void insertSession(String publicId, long playerId, long accountId, long operatorPlayerId, String aiModel,
                            String marketSession, String promptSha256, String promptText, Instant createdAt) {
    jdbc.sql("""
            INSERT INTO manual_session (public_id, player_id, account_id, operator_player_id, status, ai_model,
                                        market_session, prompt_sha256, prompt_text, created_at)
            VALUES (?, ?, ?, ?, 'PROMPTED', ?, ?, ?, ?, ?)""")
        .params(publicId, playerId, accountId, operatorPlayerId, aiModel, marketSession, promptSha256, promptText,
            Jdbc.ts(createdAt))
        .update();
  }

  /** The detail shape: prompt and reply texts included. */
  public Optional<SessionRow> session(String publicId) {
    return jdbc.sql("SELECT " + SESSION_COLUMNS + ", s.prompt_text, s.reply_text" + SESSION_FROM
            + " WHERE s.public_id = ?")
        .param(publicId).query((rs, n) -> row(rs, true)).optional();
  }

  /** The bot's sessions, newest first ({@code (created_at, id)} cursor), list shape. */
  public Page<SessionRow> sessions(long playerId, Cursor cursor, int limit) {
    StringBuilder sql = new StringBuilder("SELECT " + SESSION_COLUMNS + SESSION_FROM + " WHERE s.player_id = ?");
    List<Object> params = new ArrayList<>(List.of(playerId));
    if (cursor != null) {
      sql.append(" AND (s.created_at < ? OR (s.created_at = ? AND s.id < ?))");
      params.addAll(Arrays.asList(Jdbc.ts(cursor.at()), Jdbc.ts(cursor.at()), cursor.id()));
    }
    sql.append(" ORDER BY s.created_at DESC, s.id DESC LIMIT ").append(limit + 1);
    List<SessionRow> rows = jdbc.sql(sql.toString()).params(params).query((rs, n) -> row(rs, false)).list();
    return Page.fromLookahead(rows, limit, r -> Cursor.of(r.createdAt(), r.id()));
  }

  /** Stores a checked reply (status REPLIED); refused rows stay as they were once the session executed. */
  public void storeReply(long id, String replyText, String replySha256, String summary, String parsedJson,
                         Instant repliedAt) {
    jdbc.sql("""
            UPDATE manual_session SET reply_text = ?, reply_sha256 = ?, summary = ?, parsed_json = ?, replied_at = ?,
                                      status = 'REPLIED'
             WHERE id = ? AND executed_at IS NULL""")
        .params(replyText, replySha256, new SqlParameterValue(Types.VARCHAR, summary), parsedJson,
            Jdbc.ts(repliedAt), id)
        .update();
  }

  /** Sets {@code executed_at} and EXECUTED once (before the first order); true if this call set it. */
  public boolean markExecuted(long id, Instant at) {
    return jdbc.sql("UPDATE manual_session SET executed_at = ?, status = 'EXECUTED' WHERE id = ? "
            + "AND executed_at IS NULL")
        .params(Jdbc.ts(at), id).update() == 1;
  }

  public void storeParsed(long id, String parsedJson) {
    jdbc.sql("UPDATE manual_session SET parsed_json = ? WHERE id = ?").params(parsedJson, id).update();
  }

  public void storeJournalNote(long id, String journalNoteId) {
    jdbc.sql("UPDATE manual_session SET journal_note_id = ? WHERE id = ?").params(journalNoteId, id).update();
  }

  /** Another EXECUTED session of the bot with the same redacted reply, if any. */
  public Optional<String> executedWithReply(long playerId, String replySha256, long exceptId) {
    return jdbc.sql("""
            SELECT public_id FROM manual_session
             WHERE player_id = ? AND reply_sha256 = ? AND status = 'EXECUTED' AND id <> ?
             ORDER BY created_at DESC, id DESC LIMIT 1""")
        .params(playerId, replySha256, exceptId).query(String.class).optional();
  }

  /** The last {@code n} summaries of the bot's EXECUTED sessions, oldest first. */
  public List<ManualPromptBuilder.Summary> lastSummaries(long playerId, int n) {
    List<ManualPromptBuilder.Summary> out = new ArrayList<>(jdbc.sql("""
            SELECT created_at, summary FROM manual_session
             WHERE player_id = ? AND status = 'EXECUTED' AND summary IS NOT NULL
             ORDER BY created_at DESC, id DESC LIMIT """ + Math.max(1, n))
        .param(playerId)
        .query((rs, i) -> new ManualPromptBuilder.Summary(Jdbc.instant(rs, "created_at"), rs.getString("summary")))
        .list());
    java.util.Collections.reverse(out);
    return out;
  }

  private static SessionRow row(ResultSet rs, boolean detail) throws SQLException {
    return new SessionRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"),
        rs.getString("bot_handle"), rs.getLong("account_id"), rs.getLong("operator_player_id"),
        rs.getString("operator_handle"), rs.getString("status"), rs.getString("ai_model"),
        rs.getString("market_session"), rs.getString("prompt_sha256"), rs.getInt("prompt_chars"),
        detail ? rs.getString("prompt_text") : null, Jdbc.intOrNull(rs, "reply_chars"),
        detail ? rs.getString("reply_text") : null, rs.getString("reply_sha256"), rs.getString("summary"),
        rs.getString("parsed_json"), rs.getString("journal_note_id"), Jdbc.instant(rs, "created_at"),
        Jdbc.instant(rs, "replied_at"), Jdbc.instant(rs, "executed_at"));
  }
}
