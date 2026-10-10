package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.contract.security.PlayerPrincipal;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated {@code Authentication} for every player channel (§17): a human session, an API key or an OAuth
 * access token (§17.9). Its principal is the {@link PlayerPrincipal} that {@code CurrentPlayer} reads.
 *
 * <p>Authorities:
 * <ul>
 *   <li>{@code ROLE_PLAYER} always; {@code ROLE_ADMIN} only for a HUMAN admin <b>on a session</b>. An API key never
 *       carries {@code ROLE_ADMIN}, even the admin's own key (§17.4 layer 3, on top of {@code AdminSessionOnlyFilter}).
 *       Authorize admin work with the chain, never with {@link PlayerPrincipal#isAdmin()}, which reports the
 *       player's role for display.</li>
 *   <li>{@code AUTH_SESSION}, {@code AUTH_API_KEY} or {@code AUTH_OAUTH} (§17.9.5): how the request
 *       authenticated. An OAuth token never carries {@code ROLE_ADMIN} either.</li>
 *   <li>{@code SCOPE_<scope>} per §7.1 scope: all four for a session, the key's scopes for a key.</li>
 * </ul>
 * {@link #getName()} is the player's public id, so Spring Session JDBC's {@code PRINCIPAL_NAME} index can find (and
 * delete) a player's sessions (§17.3 password change, §10.5 freeze). Serializable for Spring Session JDBC.
 */
public final class PlayerAuthenticationToken extends AbstractAuthenticationToken {

  @Serial
  private static final long serialVersionUID = 1L;

  public static final String ROLE_PLAYER = "ROLE_PLAYER";
  public static final String ROLE_ADMIN = "ROLE_ADMIN";
  public static final String AUTH_SESSION = "AUTH_SESSION";
  public static final String AUTH_API_KEY = "AUTH_API_KEY";
  public static final String AUTH_OAUTH = "AUTH_OAUTH";
  public static final String SCOPE_PREFIX = "SCOPE_";

  private final PlayerPrincipal principal;

  public PlayerAuthenticationToken(PlayerPrincipal principal) {
    super(authoritiesFor(principal));
    this.principal = Objects.requireNonNull(principal, "principal");
    super.setAuthenticated(true);
  }

  public static String scopeAuthority(String scope) {
    return SCOPE_PREFIX + scope;
  }

  static List<GrantedAuthority> authoritiesFor(PlayerPrincipal p) {
    List<GrantedAuthority> out = new ArrayList<>();
    out.add(new SimpleGrantedAuthority(ROLE_PLAYER));
    if (p.authMethod() == AuthMethod.SESSION && p.role() == PlayerRole.ADMIN && p.kind() == PlayerKind.HUMAN) {
      out.add(new SimpleGrantedAuthority(ROLE_ADMIN));
    }
    out.add(new SimpleGrantedAuthority(switch (p.authMethod()) {
      case SESSION -> AUTH_SESSION;
      case API_KEY -> AUTH_API_KEY;
      case OAUTH -> AUTH_OAUTH;
    }));
    p.scopes().stream().sorted().forEach(s -> out.add(new SimpleGrantedAuthority(scopeAuthority(s))));
    return out;
  }

  @Override
  public PlayerPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return principal.playerPublicId();
  }

  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated) {
      throw new IllegalArgumentException("Create a new PlayerAuthenticationToken instead");
    }
    super.setAuthenticated(false);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof PlayerAuthenticationToken t && super.equals(t) && principal.equals(t.principal);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + principal.hashCode();
  }
}
