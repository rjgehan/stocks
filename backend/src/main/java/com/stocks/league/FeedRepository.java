package com.stocks.league;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.Cursor;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code feed_event} (§8.1). Written only by {@link FeedEventListener} (§12.8: the sole writer), idempotent by the
 * unique {@code source_ref}; read newest-visible first.
 */
@Repository
public class FeedRepository {

  /** One {@code feed_event} row; {@code payload} is the JSON of {@code FeedItem.payload}. */
  public record FeedRow(long id, String publicId, long playerId, Long accountId, FeedItemKind kind, String symbol,
                        String payload, String sourceRef, Instant createdAt, Instant visibleAt) {}

  private static final String COLUMNS =
      "id, public_id, player_id, account_id, event_kind, symbol, payload, source_ref, created_at, visible_at";

  private final JdbcClient jdbc;

  public FeedRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public boolean exists(String sourceRef) {
    return jdbc.sql("SELECT COUNT(*) FROM feed_event WHERE source_ref = ?").param(sourceRef)
        .query(Long.class).single() > 0;
  }

  /** Inserts unless {@code source_ref} exists (a re-delivered event is a no-op); true if a row was written. */
  public boolean insertIfAbsent(String publicId, long playerId, Long accountId, FeedItemKind kind, String symbol,
                                String payload, String sourceRef, Instant createdAt, Instant visibleAt) {
    if (exists(sourceRef)) {
      return false;
    }
    try {
      jdbc.sql("""
              INSERT INTO feed_event (public_id, player_id, account_id, event_kind, symbol, payload, source_ref,
                                      created_at, visible_at)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
          .params(publicId, playerId, new SqlParameterValue(Types.BIGINT, accountId), kind.name(),
              new SqlParameterValue(Types.VARCHAR, symbol), payload, sourceRef, Jdbc.ts(createdAt),
              Jdbc.ts(visibleAt))
          .update();
      return true;
    } catch (DuplicateKeyException e) {
      return false;                                  // a concurrent delivery of the same event won
    }
  }

  /** Visible rows ({@code visible_at ≤ now}), newest first, {@code (visible_at, id)} cursor. */
  public List<FeedRow> page(Instant now, Cursor cursor, int limitPlusOne) {
    List<Object> params = new ArrayList<>(List.of(Jdbc.ts(now)));
    String page = "";
    if (cursor != null) {
      page = " AND (visible_at < ? OR (visible_at = ? AND id < ?))";
      params.add(Jdbc.ts(cursor.at()));
      params.add(Jdbc.ts(cursor.at()));
      params.add(cursor.id());
    }
    params.add(limitPlusOne);
    return jdbc.sql("SELECT " + COLUMNS + " FROM feed_event WHERE visible_at <= ?" + page
            + " ORDER BY visible_at DESC, id DESC LIMIT ?")
        .params(params).query(FeedRepository::row).list();
  }

  /** Rows that became visible in {@code (fromExclusive, toInclusive]}, oldest first (the STOMP publisher). */
  public List<FeedRow> visibleBetween(Instant fromExclusive, Instant toInclusive) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM feed_event WHERE visible_at > ? AND visible_at <= ? "
            + "ORDER BY visible_at, id")
        .params(Jdbc.ts(fromExclusive), Jdbc.ts(toInclusive)).query(FeedRepository::row).list();
  }

  private static FeedRow row(ResultSet rs, int n) throws SQLException {
    return new FeedRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"),
        Jdbc.longOrNull(rs, "account_id"), FeedItemKind.valueOf(rs.getString("event_kind")), rs.getString("symbol"),
        rs.getString("payload"), rs.getString("source_ref"), Jdbc.instant(rs, "created_at"),
        Jdbc.instant(rs, "visible_at"));
  }
}
