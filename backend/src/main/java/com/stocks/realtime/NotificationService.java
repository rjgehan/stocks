package com.stocks.realtime;

import com.stocks.common.domain.NotificationKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.realtime.dto.NotificationDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The player's notifications (§18.1 {@code /me/notifications}, MCP {@code get_notifications} and
 * {@code mark_notifications_read}). The <b>only writer</b> of {@code notification} (§27.2). Every notification is
 * keyed by {@code (player_id, source_ref)} ({@code EXEC:<exe>}, {@code XFER:<xfr>}, {@code ORD:<ord>:<event seq>},
 * {@code VIO:<vio>}, … as §8.1 lists), so a redelivered event never notifies twice; a new row is pushed to the
 * player's {@code /user/queue/notifications} right after it is written. Read rows older than 90 days are deleted by
 * WS1's housekeeping job (§25).
 */
@Service
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  public static final int TITLE_MAX = 120;
  public static final int BODY_MAX = 500;
  public static final int PAYLOAD_MAX = 2000;
  public static final int SOURCE_REF_MAX = 64;
  /** {@code mark_notifications_read} / {@code POST /me/notifications/read}: ids per call. */
  public static final int MAX_IDS = 200;

  private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final PublicId publicId;
  private final TimeSource time;
  private final JsonMapper jsonMapper;
  private final UserPush push;

  public NotificationService(JdbcClient jdbc, PublicId publicId, TimeSource time, JsonMapper jsonMapper,
                             UserPush push) {
    this.jdbc = jdbc;
    this.publicId = publicId;
    this.time = time;
    this.jsonMapper = jsonMapper;
    this.push = push;
  }

  /**
   * Writes a notification unless {@code (playerId, sourceRef)} already exists (idempotent), then pushes it to the
   * player's {@code /user/queue/notifications}. Title and body are clipped to their columns; a payload whose JSON
   * exceeds {@value #PAYLOAD_MAX} characters is dropped (the title and body still say what happened).
   *
   * @return the new notification, or empty when it already existed
   */
  public Optional<NotificationDto> notify(long playerId, NotificationKind kind, String title, String body,
                                          Map<String, Object> payload, String sourceRef) {
    Objects.requireNonNull(kind, "kind");
    if (sourceRef == null || sourceRef.isBlank() || sourceRef.length() > SOURCE_REF_MAX) {
      throw new IllegalArgumentException("sourceRef must be 1-" + SOURCE_REF_MAX + " characters: " + sourceRef);
    }
    String id = publicId.next(Prefix.NOTIFICATION);
    Instant now = time.now();
    String cleanTitle = Text.clip(Objects.requireNonNullElse(title, kind.name()), TITLE_MAX);
    String cleanBody = Text.clip(Objects.requireNonNullElse(body, ""), BODY_MAX);
    String payloadJson = payloadJson(payload);
    // MERGE with every parameter cast (§8 portability rules): an untyped null payload would otherwise be "text" on PG.
    int inserted = jdbc.sql("""
            MERGE INTO notification t
            USING (VALUES (CAST(? AS VARCHAR(32)), CAST(? AS BIGINT), CAST(? AS VARCHAR(24)), CAST(? AS VARCHAR(120)),
                           CAST(? AS VARCHAR(500)), CAST(? AS VARCHAR(2000)), CAST(? AS VARCHAR(64)),
                           CAST(? AS TIMESTAMP WITH TIME ZONE)))
              AS v(public_id, player_id, kind, title, body, payload, source_ref, created_at)
            ON t.player_id = v.player_id AND t.source_ref = v.source_ref
            WHEN NOT MATCHED THEN
              INSERT (public_id, player_id, kind, title, body, payload, source_ref, created_at)
              VALUES (v.public_id, v.player_id, v.kind, v.title, v.body, v.payload, v.source_ref, v.created_at)""")
        .params(id, playerId, kind.name(), cleanTitle, cleanBody, payloadJson, sourceRef, Jdbc.ts(now))
        .update();
    if (inserted == 0) {
      return Optional.empty();
    }
    NotificationDto dto = new NotificationDto(id, kind, cleanTitle, cleanBody, parse(payloadJson),
        now.truncatedTo(java.time.temporal.ChronoUnit.MICROS), null);
    push.toPlayer(playerId, StompDestinations.NOTIFICATIONS_QUEUE, dto);
    return Optional.of(dto);
  }

  /** Newest first; {@code unreadOnly} keeps rows with {@code read_at IS NULL}. */
  public Page<NotificationDto> list(long playerId, boolean unreadOnly, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    StringBuilder sql = new StringBuilder("""
        SELECT id, public_id, kind, title, body, payload, created_at, read_at
          FROM notification
         WHERE player_id = :player""");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("player", playerId);
    if (unreadOnly) {
      sql.append(" AND read_at IS NULL");
    }
    if (cursor != null) {
      sql.append(" AND (created_at < :at OR (created_at = :at AND id < :id))");
      params.put("at", Jdbc.ts(cursor.at()));
      params.put("id", cursor.id());
    }
    sql.append(" ORDER BY created_at DESC, id DESC LIMIT :lim");
    params.put("lim", n + 1);
    List<Row> rows = jdbc.sql(sql.toString()).params(params).query(this::row).list();
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.createdAt(), r.id())).map(Row::dto);
  }

  /** Unread count (badge). */
  public long unreadCount(long playerId) {
    return jdbc.sql("SELECT COUNT(*) FROM notification WHERE player_id = ? AND read_at IS NULL")
        .param(playerId).query(Long.class).single();
  }

  /**
   * Marks the given notifications of {@code playerId} read (ids of other players are ignored, never an error);
   * returns how many changed. Exactly one of {@code ids} (1–{@value #MAX_IDS}) and {@code all=true} (§18.1).
   */
  public int markRead(long playerId, List<String> ids, Boolean all) {
    boolean everything = Boolean.TRUE.equals(all);
    boolean some = ids != null && !ids.isEmpty();
    if (everything == some) {
      throw ApiException.validation(some ? "all" : "ids", "Send exactly one of ids (non-empty) and all=true.");
    }
    if (everything) {
      return jdbc.sql("UPDATE notification SET read_at = :now WHERE player_id = :player AND read_at IS NULL")
          .param("now", Jdbc.ts(time.now())).param("player", playerId).update();
    }
    if (ids.size() > MAX_IDS) {
      throw ApiException.validation("ids", "At most " + MAX_IDS + " ids per call.");
    }
    List<String> clean = ids.stream().filter(Objects::nonNull).map(String::trim).distinct().toList();
    if (clean.isEmpty()) {
      throw ApiException.validation("ids", "Send at least one notification id.");
    }
    return jdbc.sql("""
            UPDATE notification SET read_at = :now
             WHERE player_id = :player AND read_at IS NULL AND public_id IN (:ids)""")
        .param("now", Jdbc.ts(time.now())).param("player", playerId).param("ids", clean).update();
  }

  // ------------------------------------------------------------------------------------------- rows

  private record Row(long id, Instant createdAt, NotificationDto dto) {}

  private Row row(ResultSet rs, int n) throws SQLException {
    Instant createdAt = Jdbc.instant(rs, "created_at");
    NotificationKind kind;
    try {
      kind = NotificationKind.valueOf(rs.getString("kind"));
    } catch (IllegalArgumentException e) {
      kind = NotificationKind.SYSTEM;
    }
    return new Row(rs.getLong("id"), createdAt, new NotificationDto(rs.getString("public_id"), kind,
        rs.getString("title"), rs.getString("body"), parse(rs.getString("payload")), createdAt,
        Jdbc.instant(rs, "read_at")));
  }

  private String payloadJson(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return null;
    }
    String json = jsonMapper.writeValueAsString(payload);
    if (json.length() > PAYLOAD_MAX) {
      log.warn("Notification payload of {} characters dropped (max {})", json.length(), PAYLOAD_MAX);
      return null;
    }
    return json;
  }

  private Map<String, Object> parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return jsonMapper.readValue(json, MAP);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
