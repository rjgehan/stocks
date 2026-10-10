package com.stocks.league;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code feed_reaction} (§16.11, Phase 2): one reaction per player per feed item. Set with a cast-everything
 * {@code MERGE} (portable H2 + PostgreSQL, §8); reacting again with the same emoji keeps its {@code created_at}, another
 * emoji replaces it (and moves the player to the end of that emoji's list). Social data, not money: written on the
 * request thread, never through the engine.
 */
@Repository
public class FeedReactionRepository {

  /** One reaction row. */
  public record ReactionRow(long feedEventId, long playerId, Reaction reaction, Instant createdAt) {}

  /** A visible feed item's ids (§16.11: only those can be reacted to). */
  public record ItemRef(long id, String publicId, long playerId) {}

  static final String MERGE = """
      MERGE INTO feed_reaction r
      USING (VALUES (CAST(? AS BIGINT), CAST(? AS BIGINT), CAST(? AS VARCHAR(8)),
                     CAST(? AS TIMESTAMP WITH TIME ZONE)))
        AS v(feed_event_id, player_id, reaction, created_at)
      ON r.feed_event_id = v.feed_event_id AND r.player_id = v.player_id
      WHEN MATCHED THEN UPDATE SET reaction = v.reaction,
        created_at = CASE WHEN r.reaction = v.reaction THEN r.created_at ELSE v.created_at END
      WHEN NOT MATCHED THEN INSERT (feed_event_id, player_id, reaction, created_at)
        VALUES (v.feed_event_id, v.player_id, v.reaction, v.created_at)""";

  private final JdbcClient jdbc;

  public FeedReactionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The feed item with this public id if it is visible at {@code now}. */
  public Optional<ItemRef> visibleItem(String publicId, Instant now) {
    if (publicId == null || publicId.isBlank() || publicId.length() > 32) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT id, public_id, player_id FROM feed_event WHERE public_id = ? AND visible_at <= ?")
        .params(publicId, Jdbc.ts(now))
        .query((rs, n) -> new ItemRef(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id")))
        .optional();
  }

  public void set(long feedEventId, long playerId, Reaction reaction, Instant at) {
    jdbc.sql(MERGE).params(feedEventId, playerId, reaction.name(), Jdbc.ts(at)).update();
  }

  /** True if a row was removed. */
  public boolean remove(long feedEventId, long playerId) {
    return jdbc.sql("DELETE FROM feed_reaction WHERE feed_event_id = ? AND player_id = ?")
        .params(feedEventId, playerId).update() > 0;
  }

  /** The reactions of the given items, keyed by feed event id, each list oldest first (then by player id). */
  public Map<Long, List<ReactionRow>> forItems(Collection<Long> feedEventIds) {
    Map<Long, List<ReactionRow>> out = new HashMap<>();
    if (feedEventIds.isEmpty()) {
      return out;
    }
    String in = feedEventIds.stream().map(id -> "?").collect(Collectors.joining(", "));
    jdbc.sql("SELECT feed_event_id, player_id, reaction, created_at FROM feed_reaction WHERE feed_event_id IN (" + in
            + ") ORDER BY feed_event_id, created_at, player_id")
        .params(new ArrayList<Object>(feedEventIds))
        .query((rs, n) -> new ReactionRow(rs.getLong("feed_event_id"), rs.getLong("player_id"),
            Reaction.valueOf(rs.getString("reaction")), Jdbc.instant(rs, "created_at")))
        .list()
        .forEach(r -> out.computeIfAbsent(r.feedEventId(), k -> new ArrayList<>()).add(r));
    return out;
  }
}
