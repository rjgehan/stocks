package com.stocks.security;

import com.stocks.common.error.BearerChallenge;
import com.stocks.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The security entry point and access-denied handler of every chain: both answer with the §6.5 problem body.
 * <ul>
 *   <li>not authenticated → 401 {@code UNAUTHENTICATED} (chain 2 adds {@code WWW-Authenticate: Bearer realm="stocks"},
 *       §17.1, with {@code resource_metadata} while OAuth is on, §17.9.5);</li>
 *   <li>CSRF missing or wrong → 403 {@code CSRF_INVALID}, except for an anonymous caller on a non-public route, who
 *       gets the 401 above (a bot that forgot its key should not be told about CSRF);</li>
 *   <li>a key without the route's scope → 403 {@code FORBIDDEN_SCOPE}; a key on a session-only route, or a non-admin
 *       on the admin chain → 403 {@code FORBIDDEN}.</li>
 * </ul>
 */
public final class SecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ProblemWriter problems;
  private final String bearerChallenge;
  private final RequestMatcher publicRoutes;

  /**
   * @param bearerChallenge add {@code WWW-Authenticate: Bearer realm="stocks"} to 401s (chain 2)
   * @param publicRoutes    routes anonymous callers may use (their CSRF failures stay {@code CSRF_INVALID})
   */
  public SecurityErrors(ProblemWriter problems, boolean bearerChallenge, RequestMatcher publicRoutes) {
    this(problems, bearerChallenge ? BearerChallenge.DEFAULT : null, publicRoutes);
  }

  /**
   * @param challengeOrNull the {@code WWW-Authenticate} challenge of 401s (chain 2: with {@code resource_metadata}
   *                        while OAuth is on, §17.9.5), or null for none
   */
  public SecurityErrors(ProblemWriter problems, BearerChallenge challengeOrNull, RequestMatcher publicRoutes) {
    this.problems = problems;
    this.bearerChallenge = challengeOrNull == null ? null : challengeOrNull.value();
    this.publicRoutes = publicRoutes;
  }

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
      throws IOException {
    unauthenticated(request, response);
  }

  @Override
  public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
      throws IOException {
    if (e instanceof CsrfException) {
      var auth = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
      if (!RouteAuthorization.isAuthenticated(auth) && !publicRoutes.matches(request)) {
        unauthenticated(request, response);
        return;
      }
      problems.write(request, response, ErrorCode.CSRF_INVALID,
          "Missing or invalid CSRF token: send the XSRF-TOKEN cookie value as the X-XSRF-TOKEN header "
              + "(GET /api/v1/auth/csrf issues the cookie).");
      return;
    }
    if (e instanceof AuthorizationDeniedException denied) {
      AuthorizationResult result = denied.getAuthorizationResult();
      if (result instanceof RouteAuthorization.MissingScope missing) {
        problems.write(request, response, ErrorCode.FORBIDDEN_SCOPE,
            "This API key lacks the '" + missing.scope() + "' scope.");
        return;
      }
      if (result instanceof RouteAuthorization.SessionRequired) {
        problems.write(request, response, ErrorCode.FORBIDDEN,
            "This endpoint needs a browser session; API keys cannot use it.");
        return;
      }
    }
    problems.write(request, response, ErrorCode.FORBIDDEN, "You are not allowed to do this.");
  }

  private void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
    problems.write(request, response, ErrorCode.UNAUTHENTICATED,
        "Authentication required: log in, or send Authorization: Bearer stk_….", bearerChallenge);
  }
}
