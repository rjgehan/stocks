package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.PlayerPrincipal;
import java.io.Serial;
import java.util.function.Supplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Per-route rules of chain 2 (§18.1, §18.2): the §7.1 scope a route needs, and the routes that need a browser
 * session. A denial carries its reason ({@link MissingScope}, {@link SessionRequired}) so {@link SecurityErrors} can
 * answer 403 {@code FORBIDDEN_SCOPE} or 403 {@code FORBIDDEN}; an anonymous caller is denied plainly, which Spring
 * Security turns into the 401 entry point.
 *
 * <p>A session holds every scope ({@link PlayerAuthenticationToken}); an API key holds its own. Authentications that
 * are not a {@link PlayerAuthenticationToken} (e.g. MockMvc {@code user()}) are checked by their {@code SCOPE_*}
 * authorities and count as a session unless they carry {@code AUTH_API_KEY}.
 */
public final class RouteAuthorization {

  private RouteAuthorization() {}

  /** Denied because the API key lacks {@link #scope()}. */
  public static final class MissingScope extends AuthorizationDecision {
    @Serial
    private static final long serialVersionUID = 1L;
    private final String scope;

    MissingScope(String scope) {
      super(false);
      this.scope = scope;
    }

    public String scope() {
      return scope;
    }
  }

  /** Denied because the route needs a browser session and the request used an API key. */
  public static final class SessionRequired extends AuthorizationDecision {
    @Serial
    private static final long serialVersionUID = 1L;

    SessionRequired() {
      super(false);
    }
  }

  private static final AuthorizationDecision GRANTED = new AuthorizationDecision(true);
  private static final AuthorizationDecision UNAUTHENTICATED = new AuthorizationDecision(false);

  /** Any authenticated player holding {@code scope}. */
  public static AuthorizationManager<RequestAuthorizationContext> scope(String scope) {
    return (auth, ctx) -> decide(auth, scope, false);
  }

  /** Any authenticated player on a browser session. */
  public static AuthorizationManager<RequestAuthorizationContext> session() {
    return (auth, ctx) -> decide(auth, null, true);
  }

  /** A browser session that also holds {@code scope} (sessions hold every scope, so only the session part bites). */
  public static AuthorizationManager<RequestAuthorizationContext> sessionWithScope(String scope) {
    return (auth, ctx) -> decide(auth, scope, true);
  }

  /**
   * Chain 1 (§17.1): {@code ROLE_ADMIN} (a human admin on a browser session) whose player is still ACTIVE. A FROZEN
   * human may log in to look (§17.3), but a frozen admin never acts as one, so the kill switch also holds for an admin
   * and cannot be undone by its target. The status comes from {@link PlayerQueries#status}'s cache, which every
   * status change evicts synchronously (§10.5).
   */
  public static AuthorizationManager<RequestAuthorizationContext> activeAdmin(PlayerQueries players) {
    AuthorityAuthorizationManager<RequestAuthorizationContext> admin = AuthorityAuthorizationManager.hasRole("ADMIN");
    return (supplier, ctx) -> {
      AuthorizationResult role = admin.authorize(supplier, ctx);
      if (role == null || !role.isGranted()) {
        return role;
      }
      if (supplier.get().getPrincipal() instanceof PlayerPrincipal p
          && players.status(p.playerId()) != PlayerStatus.ACTIVE) {
        return new AuthorizationDecision(false);
      }
      return GRANTED;
    };
  }

  static AuthorizationResult decide(Supplier<? extends Authentication> supplier, String scope, boolean sessionOnly) {
    Authentication auth = supplier.get();
    if (!isAuthenticated(auth)) {
      return UNAUTHENTICATED;
    }
    if (sessionOnly && !isSession(auth)) {
      return new SessionRequired();
    }
    if (scope != null && !hasScope(auth, scope)) {
      return new MissingScope(scope);
    }
    return GRANTED;
  }

  public static boolean isAuthenticated(Authentication auth) {
    return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
  }

  static boolean isSession(Authentication auth) {
    if (auth.getPrincipal() instanceof PlayerPrincipal p) {
      return p.authMethod() == AuthMethod.SESSION;
    }
    return auth.getAuthorities().stream()
        .noneMatch(a -> PlayerAuthenticationToken.AUTH_API_KEY.equals(a.getAuthority()));
  }

  static boolean hasScope(Authentication auth, String scope) {
    if (auth.getPrincipal() instanceof PlayerPrincipal p) {
      return p.hasScope(scope);
    }
    String wanted = PlayerAuthenticationToken.scopeAuthority(scope);
    return auth.getAuthorities().stream().anyMatch(a -> wanted.equals(a.getAuthority()));
  }
}
