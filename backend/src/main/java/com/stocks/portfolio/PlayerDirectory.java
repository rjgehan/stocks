package com.stocks.portfolio;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Public player data for the league (§16.5, §18.3 {@code PlayerSummary}), read straight from {@code player} and
 * {@code account} (read-only; WS1 and WS3 own the writes) so the 5 s leaderboard recompute needs one query, not one
 * per player. {@code resets = max(run_no) − 1}. Never exposes credentials, preferences or status details beyond
 * what the league shows.
 */
@Component
public class PlayerDirectory {

  /** One player as the league sees it. */
  public record PlayerRow(long id, String publicId, String handle, String displayName, PlayerKind kind,
                          String aiModel, String avatarSeed, Instant createdAt, PlayerStatus status,
                          Long ownerPlayerId, int resets) {

    public PlayerSummaryDto summary() {
      return new PlayerSummaryDto(publicId, handle, displayName, kind, aiModel, avatarSeed, createdAt, resets);
    }

    /** ACTIVE or FROZEN (§16.5 population; DISABLED players are hidden). */
    public boolean listed() {
      return status != PlayerStatus.DISABLED;
    }
  }

  private static final String SELECT = """
      SELECT p.id, p.public_id, p.handle, p.display_name, p.kind, p.ai_model, p.avatar_seed, p.created_at, p.status,
             p.owner_player_id, COALESCE(r.max_run, 1) - 1 AS resets
        FROM player p
        LEFT JOIN (SELECT player_id, MAX(run_no) AS max_run FROM account GROUP BY player_id) r ON r.player_id = p.id""";

  private final JdbcClient jdbc;

  public PlayerDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every player, by id. */
  public Map<Long, PlayerRow> all() {
    Map<Long, PlayerRow> out = new LinkedHashMap<>();
    jdbc.sql(SELECT + " ORDER BY p.handle").query(PlayerDirectory::row).list().forEach(p -> out.put(p.id(), p));
    return out;
  }

  /** ACTIVE and FROZEN players, optionally of one kind, by handle (§18.2 {@code GET /players}). */
  public List<PlayerRow> listed(PlayerKind kindOrNull) {
    return all().values().stream()
        .filter(PlayerRow::listed)
        .filter(p -> kindOrNull == null || p.kind() == kindOrNull)
        .toList();
  }

  /** Handles match lower-cased (§17.3). */
  public Optional<PlayerRow> byHandle(String handle) {
    if (handle == null || handle.isBlank()) {
      return Optional.empty();
    }
    return jdbc.sql(SELECT + " WHERE p.handle = ?").param(handle.trim().toLowerCase(Locale.ROOT))
        .query(PlayerDirectory::row).optional();
  }

  public Optional<PlayerRow> byId(long playerId) {
    return jdbc.sql(SELECT + " WHERE p.id = ?").param(playerId).query(PlayerDirectory::row).optional();
  }

  private static PlayerRow row(ResultSet rs, int n) throws SQLException {
    return new PlayerRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("handle"),
        rs.getString("display_name"), PlayerKind.valueOf(rs.getString("kind")), rs.getString("ai_model"),
        rs.getString("avatar_seed"), Jdbc.instant(rs, "created_at"), PlayerStatus.valueOf(rs.getString("status")),
        Jdbc.longOrNull(rs, "owner_player_id"), rs.getInt("resets"));
  }
}
