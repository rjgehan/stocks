package com.stocks.security;

import com.stocks.common.error.BearerChallenge;
import com.stocks.common.error.ErrorCode;
import com.stocks.oauth.AccessTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chain 2's bearer branch (§17.1): {@code Authorization: Bearer stk_…} authenticates the request statelessly through
 * {@link ApiKeyAuthenticationProvider}; any other bearer shaped like a JWS is an OAuth access token (§17.9.5) for
 * {@link OAuthTokenAuthenticationProvider}, accepted on {@code /mcp} only (its audience) while OAuth is on. The resulting context replaces whatever a session cookie would have loaded
 * (cookies are ignored on the key path), is never saved, and the request skips CSRF (see {@link #BEARER}). A bad key
 * is answered here with the §7.2 problem and charged to the caller IP's anonymous bucket (§17.5), and an IP whose
 * bucket is empty gets 429 before any lookup; a request without a bearer header passes through to the session path.
 *
 * <p>Not a Spring bean on purpose: a {@code Filter} bean would also be registered on the servlet container.
 */
public final class BearerTokenFilter extends OncePerRequestFilter {

  /** Requests carrying {@code Authorization: Bearer …} (case-insensitive scheme). */
  public static final RequestMatcher BEARER = BearerTokenFilter::hasBearer;

  private final ApiKeyAuthenticationProvider provider;
  private final OAuthTokenAuthenticationProvider oauth;
  private final BearerChallenge challenge;
  private final ProblemWriter problems;
  private final AuthFailureLimiter failures;
  private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

  public BearerTokenFilter(ApiKeyAuthenticationProvider provider, ProblemWriter problems) {
    this(provider, problems, null);
  }

  /** With {@code limits}, bad keys are charged to the caller IP's anonymous bucket (§17.5). */
  public BearerTokenFilter(ApiKeyAuthenticationProvider provider, ProblemWriter problems,
                           RateLimitServiceImpl limits) {
    this(provider, null, BearerChallenge.DEFAULT, problems, limits);
  }

  /**
   * @param oauthOrNull the OAuth access-token provider, or null when OAuth is off (JWTs are then unknown keys)
   * @param challenge   the {@code WWW-Authenticate} challenge of this chain's 401s
   */
  public BearerTokenFilter(ApiKeyAuthenticationProvider provider, OAuthTokenAuthenticationProvider oauthOrNull,
                           BearerChallenge challenge, ProblemWriter problems, RateLimitServiceImpl limits) {
    this.provider = provider;
    this.oauth = oauthOrNull;
    this.challenge = challenge;
    this.problems = problems;
    this.failures = new AuthFailureLimiter(limits, problems);
  }

  /** {@code /mcp} (and anything below it), the audience of OAuth access tokens. */
  static boolean isMcp(HttpServletRequest request) {
    String path = request.getRequestURI();
    String context = request.getContextPath();
    if (path != null && context != null && !context.isEmpty() && path.startsWith(context)) {
      path = path.substring(context.length());
    }
    return path != null && (path.equals("/mcp") || path.startsWith("/mcp/"));
  }

  static boolean hasBearer(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!hasBearer(request)) {
      chain.doFilter(request, response);
      return;
    }
    if (failures.refuse(request, response)) {
      return;   // this IP keeps sending bad credentials: no key lookup at all until its bucket refills
    }
    String token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(7).trim();
    Authentication result;
    try {
      if (oauth != null && !token.startsWith(ApiKeys.PREFIX) && AccessTokens.looksLikeJws(token)) {
        if (!isMcp(request)) {
          throw new ApiKeyAuthenticationException(ErrorCode.UNAUTHENTICATED,
              "OAuth access tokens are accepted on /mcp only; use an API key (stk_…) for the REST API.");
        }
        result = oauth.authenticate(token);
      } else {
        result = provider.authenticate(new ApiKeyAuthenticationToken(token));
      }
    } catch (ApiKeyAuthenticationException e) {
      contexts.clearContext();
      if (failures.charge(request, response)) {
        return;
      }
      problems.write(request, response, e.code(), e.getMessage(),
          e.code().status() == 401 ? challenge.invalidToken() : null);
      return;
    }
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(result);
    contexts.setContext(context);
    chain.doFilter(request, response);
  }
}
