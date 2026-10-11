package com.stocks.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.oauth.AccessTokens;
import com.stocks.oauth.OAuthGrantsRevoked;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Chain 2's JWT branch (§17.9.5): verifies an OAuth access token ({@link AccessTokens}: RS256, {@code at+jwt},
 * {@code iss}, {@code aud = ${public-url}/mcp}, {@code exp}/{@code nbf} on {@link TimeSource}) and then, on every
 * request, its grant ({@code gid}) with the consenting human and the acting player:
 * <ul>
 *   <li>unknown or revoked grant, a {@code sub} that is not the grant's actor, or a bot that no longer belongs to the
 *       human → 401 {@code UNAUTHENTICATED} ({@code error="invalid_token"});</li>
 *   <li>the actor or the human DISABLED → 403 {@code PLAYER_DISABLED}; FROZEN → 403 {@code PLAYER_FROZEN}.</li>
 * </ul>
 * The principal acts as the grant's actor with {@code scopes = token ∩ grant} ({@code trade} removed from a grant
 * acting as the human while the admin has their API trading off, §17.9.3), {@link AuthMethod#OAUTH} and the token's
 * {@code client_id}; its authorities never include {@code ROLE_ADMIN} (§17.4 layer 3), even for the admin's
 * own grant. Grant rows are cached for {@code stocks.oauth.grant-cache-ttl} (60 s; 0 in tests) and evicted at once by
 * every revocation ({@link OAuthGrantsRevoked}); player statuses come from {@link PlayerQueries#status}.
 */
public class OAuthTokenAuthenticationProvider {

  private final AccessTokens tokens;
  private final JdbcClient jdbc;
  private final PlayerQueries players;
  private final TimeSource time;
  private final ActivityRecorder activity;
  private final Cache<String, GrantRow> cache;

  public OAuthTokenAuthenticationProvider(AccessTokens tokens, JdbcClient jdbc, PlayerQueries players,
                                          TimeSource time, Duration grantCacheTtl, ActivityRecorder activity) {
    this.tokens = tokens;
    this.jdbc = jdbc;
    this.players = players;
    this.time = time;
    this.activity = activity;
    this.cache = grantCacheTtl == null || grantCacheTtl.isZero() || grantCacheTtl.isNegative() ? null
        : Caffeine.newBuilder().expireAfterWrite(grantCacheTtl).maximumSize(10_000).build();
  }

  record GrantRow(long id, String publicId, String clientId, Set<String> scopes, Instant revokedAt, long humanId,
                  long actorId, String actorPublicId, String actorHandle, PlayerKind actorKind, PlayerRole actorRole,
                  Long actorOwnerId, boolean humanApiTradingAllowed) {}

  /**
   * Authenticates a JWT access token.
   *
   * @throws ApiKeyAuthenticationException with the §7.2 code to answer ({@code UNAUTHENTICATED},
   *                                       {@code PLAYER_FROZEN}, {@code PLAYER_DISABLED})
   */
  public PlayerAuthenticationToken authenticate(String token) {
    Jwt jwt;
    try {
      jwt = tokens.decode(token);
    } catch (JwtException e) {
      throw invalid(e.getMessage());
    }
    String gid = jwt.getClaimAsString("gid");
    GrantRow grant = gid == null ? null : lookup(gid).orElse(null);
    if (grant == null || grant.revokedAt() != null) {
      throw invalid("This access token's grant was revoked.");
    }
    if (!grant.actorPublicId().equals(jwt.getSubject())) {
      throw invalid("This access token does not match its grant.");
    }
    if (grant.actorId() != grant.humanId()
        && (grant.actorOwnerId() == null || grant.actorOwnerId() != grant.humanId())) {
      throw invalid("The bot of this grant no longer belongs to the person who connected it.");
    }
    for (long id : grant.actorId() == grant.humanId() ? new long[] {grant.actorId()}
        : new long[] {grant.actorId(), grant.humanId()}) {
      PlayerStatus status = players.status(id);
      if (status == PlayerStatus.DISABLED) {
        throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
      }
      if (status == PlayerStatus.FROZEN) {
        throw new ApiKeyAuthenticationException(ErrorCode.PLAYER_FROZEN, "This player is frozen by the admin.");
      }
    }
    Set<String> scopes = scopes(jwt.getClaimAsString("scope"));
    scopes.retainAll(grant.scopes());
    if (grant.actorId() == grant.humanId() && !grant.humanApiTradingAllowed()) {
      scopes.remove(Scopes.TRADE);             // §17.9.3: trading as myself follows the current rule, not the consent
    }
    PlayerPrincipal principal = new PlayerPrincipal(grant.actorId(), grant.actorPublicId(), grant.actorHandle(),
        grant.actorKind(), grant.actorRole(), scopes, AuthMethod.OAUTH, null, jwt.getClaimAsString("client_id"),
        time.now());
    if (activity != null) {
      activity.oauthGrantUsed(grant.id());
    }
    return new PlayerAuthenticationToken(principal);
  }

  /** Drops revoked grants from the cache (§17.9.5: revocation takes effect on the next request). */
  @EventListener
  public void onRevoked(OAuthGrantsRevoked e) {
    if (cache != null) {
      cache.invalidateAll(e.grantPublicIds());
    }
  }

  static Set<String> scopes(String claim) {
    if (claim == null || claim.isBlank()) {
      return new java.util.HashSet<>();
    }
    return Arrays.stream(claim.trim().split("\\s+")).filter(Scopes.ALL::contains)
        .collect(Collectors.toCollection(java.util.HashSet::new));
  }

  private Optional<GrantRow> lookup(String gid) {
    if (cache == null) {
      return find(gid);
    }
    GrantRow cached = cache.getIfPresent(gid);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<GrantRow> row = find(gid);
    row.ifPresent(r -> cache.put(gid, r));
    return row;
  }

  private Optional<GrantRow> find(String gid) {
    return jdbc.sql("""
            SELECT g.id, g.public_id, g.client_id, g.scopes, g.revoked_at, g.human_player_id, g.actor_player_id,
                   a.public_id AS actor_public_id, a.handle, a.kind, a.role, a.owner_player_id,
                   h.api_trading_allowed AS human_api_trading
              FROM oauth_grant g JOIN player a ON a.id = g.actor_player_id
                                 JOIN player h ON h.id = g.human_player_id
             WHERE g.public_id = ?""")
        .param(gid)
        .query((rs, n) -> new GrantRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("client_id"),
            Set.copyOf(scopes(rs.getString("scopes"))), Jdbc.instant(rs, "revoked_at"), rs.getLong("human_player_id"),
            rs.getLong("actor_player_id"), rs.getString("actor_public_id"), rs.getString("handle"),
            PlayerKind.valueOf(rs.getString("kind")), PlayerRole.valueOf(rs.getString("role")),
            Jdbc.longOrNull(rs, "owner_player_id"), rs.getBoolean("human_api_trading")))
        .optional();
  }

  private static ApiKeyAuthenticationException invalid(String detail) {
    return new ApiKeyAuthenticationException(ErrorCode.UNAUTHENTICATED,
        detail == null ? "Invalid access token." : detail);
  }
}
