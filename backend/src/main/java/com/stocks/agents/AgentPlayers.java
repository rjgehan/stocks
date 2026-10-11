package com.stocks.agents;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Player lookups for the Phase 2 agent features (§20.6, §20.7, §17.10): the internal row behind a handle and the
 * public {@link PlayerSummaryDto} (WS1's {@link PlayerQueries}). Read-only.
 */
@Component
public class AgentPlayers {

  /** A player row: internal id, public id, handle, kind, status and the owning human of an AI player. */
  public record PlayerRef(long id, String publicId, String handle, PlayerKind kind, PlayerStatus status,
                          Long ownerId) {

    /** DISABLED players are hidden from the league (§10.5). */
    public boolean listed() {
      return status != PlayerStatus.DISABLED;
    }
  }

  private final JdbcClient jdbc;
  private final PlayerQueries players;

  public AgentPlayers(JdbcClient jdbc, PlayerQueries players) {
    this.jdbc = jdbc;
    this.players = players;
  }

  private static final String COLUMNS = "id, public_id, handle, kind, status, owner_player_id";

  public Optional<PlayerRef> byHandle(String handle) {
    if (handle == null || handle.isBlank() || handle.length() > 40) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE handle = ?")
        .param(handle.strip().toLowerCase(Locale.ROOT))
        .query((rs, n) -> row(rs))
        .optional();
  }

  /** The listed (not DISABLED) player with this handle, else {@code PLAYER_NOT_FOUND}. */
  public PlayerRef requireListed(String handle) {
    return byHandle(handle).filter(PlayerRef::listed)
        .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player '" + clip(handle) + "'."));
  }

  public Optional<PlayerRef> byId(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE id = ?").param(id)
        .query((rs, n) -> row(rs)).optional();
  }

  /** Every AI player, by handle. */
  public List<PlayerRef> aiPlayers() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE kind = 'AI' ORDER BY handle")
        .query((rs, n) -> row(rs)).list();
  }

  /** The ids of the AI players {@code ownerId} owns. */
  public List<Long> botsOwnedBy(long ownerId) {
    return jdbc.sql("SELECT id FROM player WHERE owner_player_id = ? AND kind = 'AI' ORDER BY id")
        .param(ownerId).query(Long.class).list();
  }

  /** The public summary of a player (WS1); a minimal one if the lookup has nothing. */
  public PlayerSummaryDto summary(long playerId) {
    return players.byId(playerId).orElseGet(() -> byId(playerId)
        .map(p -> new PlayerSummaryDto(p.publicId(), p.handle(), p.handle(), p.kind(), null, null, null, 0))
        .orElse(null));
  }

  /** Summaries for many players, each looked up once. */
  public Map<Long, PlayerSummaryDto> summaries(Iterable<Long> ids) {
    Map<Long, PlayerSummaryDto> out = new HashMap<>();
    for (Long id : ids) {
      if (id != null) {
        out.computeIfAbsent(id, this::summary);
      }
    }
    return out;
  }

  private static PlayerRef row(java.sql.ResultSet rs) throws java.sql.SQLException {
    long owner = rs.getLong("owner_player_id");
    return new PlayerRef(rs.getLong("id"), rs.getString("public_id"), rs.getString("handle"),
        PlayerKind.valueOf(rs.getString("kind")), PlayerStatus.valueOf(rs.getString("status")),
        rs.wasNull() ? null : owner);
  }

  private static String clip(String s) {
    if (s == null) {
      return "";
    }
    String t = s.strip();
    return t.length() <= 40 ? t : t.substring(0, 40) + "…";
  }
}
