package com.stocks.security;

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
 * {@link ApiKeyAuthenticationProvider}. The resulting context replaces whatever a session cookie would have loaded
 * (cookies are ignored on the key path), is never saved, and the request skips CSRF (see {@link #BEARER}). A bad key
 * is answered here with the §7.2 problem; a request without a bearer header passes through to the session path.
 *
 * <p>Not a Spring bean on purpose: a {@code Filter} bean would also be registered on the servlet container.
 */
public final class BearerTokenFilter extends OncePerRequestFilter {

  /** Requests carrying {@code Authorization: Bearer …} (case-insensitive scheme). */
  public static final RequestMatcher BEARER = BearerTokenFilter::hasBearer;

  private final ApiKeyAuthenticationProvider provider;
  private final ProblemWriter problems;
  private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

  public BearerTokenFilter(ApiKeyAuthenticationProvider provider, ProblemWriter problems) {
    this.provider = provider;
    this.problems = problems;
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
    String token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(7).trim();
    Authentication result;
    try {
      result = provider.authenticate(new ApiKeyAuthenticationToken(token));
    } catch (ApiKeyAuthenticationException e) {
      contexts.clearContext();
      String challenge = e.code().status() == 401
          ? ProblemWriter.BEARER_CHALLENGE + ", error=\"invalid_token\"" : null;
      problems.write(request, response, e.code(), e.getMessage(), challenge);
      return;
    }
    SecurityContext context = contexts.createEmptyContext();
    context.setAuthentication(result);
    contexts.setContext(context);
    chain.doFilter(request, response);
  }
}
