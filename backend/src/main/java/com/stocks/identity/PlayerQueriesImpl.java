package com.stocks.identity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link PlayerQueries} (§5.3): the public face of players ({@code PlayerSummary}, §18.3) for the league, feed,
 * profiles and MCP, and the player status that every API-key request checks. {@code resets} is
 * {@code max(account.run_no) − 1} (a read-only lookup of WS3's table; 0 before the first account).
 *
 * <p>{@link #status} is cached for {@code stocks.security.player-status-cache-ttl} (60 s);
 * {@code admin.AdminPlayerService} calls {@link #evict} synchronously on every status, role or freeze change, before it
 * publishes {@code PlayerStatusChanged}, so a freeze takes effect on the very next request.
 */
@Service
public class PlayerQueriesImpl implements PlayerQueries {

  private static final String SUMMARY = """
      SELECT p.id, p.public_id, p.handle, p.display_name, p.kind, p.ai_model, p.avatar_seed, p.created_at,
             COALESCE((SELECT MAX(a.run_no) FROM account a WHERE a.player_id = p.id), 1) - 1 AS resets
        FROM player p""";

  private final JdbcClient jdbc;
  private final Cache<Long, PlayerStatus> statuses;

  public PlayerQueriesImpl(JdbcClient jdbc, StocksProperties properties) {
    this.jdbc = jdbc;
    Duration ttl = properties.security().playerStatusCacheTtl();
    this.statuses = ttl == null || ttl.isZero() || ttl.isNegative() ? null
        : Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(100_000).build();
  }

  @Override
  public Optional<PlayerSummaryDto> byHandle(String handle) {
    String h = PlayerService.normalizeHandle(handle);
    if (h == null) {
      return Optional.empty();
    }
    return jdbc.sql(SUMMARY + " WHERE p.handle = ?").param(h).query(PlayerQueriesImpl::summary).optional();
  }

  @Override
  public Optional<PlayerSummaryDto> byId(long playerId) {
    return jdbc.sql(SUMMARY + " WHERE p.id = ?").param(playerId).query(PlayerQueriesImpl::summary).optional();
  }

  /** Sorted by handle. {@code includeInactive=false} hides DISABLED players (FROZEN ones stay, §16.5). */
  @Override
  public List<PlayerSummaryDto> all(PlayerKind kindOrNull, boolean includeInactive) {
    StringBuilder sql = new StringBuilder(SUMMARY).append(" WHERE 1 = 1");
    List<Object> params = new ArrayList<>();
    if (kindOrNull != null) {
      sql.append(" AND p.kind = ?");
      params.add(kindOrNull.name());
    }
    if (!includeInactive) {
      sql.append(" AND p.status <> 'DISABLED'");
    }
    sql.append(" ORDER BY p.handle");
    return jdbc.sql(sql.toString()).params(params).query(PlayerQueriesImpl::summary).list();
  }

  /**
   * The player's status (cached).
   *
   * @throws ApiException 404 {@code PLAYER_NOT_FOUND}
   */
  @Override
  public PlayerStatus status(long playerId) {
    if (statuses == null) {
      return load(playerId);
    }
    PlayerStatus cached = statuses.getIfPresent(playerId);
    if (cached != null) {
      return cached;
    }
    PlayerStatus fresh = load(playerId);
    statuses.put(playerId, fresh);
    return fresh;
  }

  /** Drops the cached status of one player (called synchronously by every status, role or freeze change). */
  public void evict(long playerId) {
    if (statuses != null) {
      statuses.invalidate(playerId);
    }
  }

  private PlayerStatus load(long playerId) {
    return jdbc.sql("SELECT status FROM player WHERE id = ?").param(playerId)
        .query((rs, n) -> PlayerStatus.valueOf(rs.getString(1))).optional()
        .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player with id " + playerId + "."));
  }

  static PlayerSummaryDto summary(ResultSet rs, int rowNum) throws SQLException {
    return new PlayerSummaryDto(rs.getString("public_id"), rs.getString("handle"), rs.getString("display_name"),
        PlayerKind.valueOf(rs.getString("kind")), rs.getString("ai_model"), rs.getString("avatar_seed"),
        Jdbc.instant(rs, "created_at"), Math.max(0, rs.getInt("resets")));
  }
}
