package com.stocks.contract.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/**
 * The authenticated player (§5.3, §17). Built by WS1's authentication (session, API key, Phase 2 OAuth) and read
 * through {@link CurrentPlayer}. {@code apiKeyId} is set for {@link AuthMethod#API_KEY}; {@code oauthClientId} for
 * {@link AuthMethod#OAUTH}. {@code authenticatedAt} drives {@code @RecentAuth}. Serializable because Spring Session JDBC
 * Java-serializes the {@code SecurityContext} of a human session.
 */
public record PlayerPrincipal(long playerId, String playerPublicId, String handle, PlayerKind kind, PlayerRole role,
                              Set<String> scopes, AuthMethod authMethod, Long apiKeyId, String oauthClientId,
                              Instant authenticatedAt) implements Serializable {

  public PlayerPrincipal {
    scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
  }

  /** True if the principal carries the scope (§7.1 API scopes, see {@link Scopes}). */
  public boolean hasScope(String scope) {
    return scopes.contains(scope);
  }

  public boolean isAdmin() {
    return role == PlayerRole.ADMIN;
  }

  public boolean isAi() {
    return kind == PlayerKind.AI;
  }
}
