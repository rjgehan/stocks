package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.security.PlayerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chain 2's read limiter (§17.5), placed after authorization so only requests that would be served are charged:
 * <ul>
 *   <li>an authenticated player: one token of the player's read bucket, or of the heavy-read bucket for bars,
 *       portfolio and player history, the leaderboard and player profiles; web sessions and API keys/MCP have separate
 *       limits, each shared by all of the player's sessions or keys. Order writes ({@code POST /orders},
 *       {@code PATCH}/{@code DELETE /orders…}, position close) are <b>not</b> charged here: {@code OrderGate} charges
 *       the write bucket once per write, after its idempotency fast path, so replays stay free. Every {@code /mcp}
 *       call counts as one read; order tools additionally go through {@code OrderGate}.</li>
 *   <li>an anonymous caller, per client IP: {@code POST /auth/login} 10/min, {@code POST /auth/signup} 5/hour,
 *       anything else 300/min.</li>
 * </ul>
 * Every charged response carries {@code X-RateLimit-Limit} (the bucket's rate per period), {@code X-RateLimit-Remaining}
 * and {@code X-RateLimit-Reset} (seconds until the bucket is full again). Over the limit: 429 {@code RATE_LIMITED} with
 * {@code Retry-After} and {@code retryAfterS}. The admin chain is never limited (§17.5). Not a Spring bean.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

  public static final String LIMIT = "X-RateLimit-Limit";
  public static final String REMAINING = "X-RateLimit-Remaining";
  public static final String RESET = "X-RateLimit-Reset";

  private static final Pattern HEAVY = Pattern.compile(
      "^/api/v1/(bars/.*|account/history|leaderboard|players/[^/]+(/history|/trades)?)$");
  private static final Pattern ORDER_WRITE_PATH = Pattern.compile(
      "^/api/v1/(orders|orders/[^/]+|account/positions/[^/]+/close)$");

  private final RateLimitServiceImpl limits;
  private final ActivityRecorder activity;
  private final ProblemWriter problems;

  public RateLimitFilter(RateLimitServiceImpl limits, ActivityRecorder activity, ProblemWriter problems) {
    this.limits = limits;
    this.activity = activity;
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
    PlayerPrincipal principal = RouteAuthorization.isAuthenticated(auth) && auth.getPrincipal() instanceof PlayerPrincipal p
        ? p : null;
    if (principal != null) {
      activity.seen(principal, ClientIp.resolve(request));
    }
    if (!limits.enabled()) {
      chain.doFilter(request, response);
      return;
    }
    String path = path(request);
    RateLimitServiceImpl.Decision decision;
    if (principal != null) {
      if (isOrderWrite(request.getMethod(), path)) {
        chain.doFilter(request, response);
        return;
      }
      RateLimitServiceImpl.ReadClass readClass = HttpMethod.GET.matches(request.getMethod())
          && HEAVY.matcher(path).matches() ? RateLimitServiceImpl.ReadClass.HEAVY : RateLimitServiceImpl.ReadClass.READ;
      decision = limits.consumeRead(principal.playerId(), principal.authMethod() != AuthMethod.SESSION, readClass);
    } else {
      RateLimitServiceImpl.IpClass ipClass = RateLimitServiceImpl.IpClass.ANONYMOUS;
      if (HttpMethod.POST.matches(request.getMethod()) && "/api/v1/auth/login".equals(path)) {
        ipClass = RateLimitServiceImpl.IpClass.LOGIN;
      } else if (HttpMethod.POST.matches(request.getMethod()) && "/api/v1/auth/signup".equals(path)) {
        ipClass = RateLimitServiceImpl.IpClass.SIGNUP;
      }
      decision = limits.consumeIp(ClientIp.resolve(request), ipClass);
    }
    response.setHeader(LIMIT, Long.toString(decision.limit()));
    response.setHeader(REMAINING, Long.toString(decision.remaining()));
    response.setHeader(RESET, Long.toString(decision.resetSeconds()));
    if (!decision.allowed()) {
      problems.write(request, response, ErrorCode.RATE_LIMITED,
          "Rate limit exceeded; retry in " + decision.retryAfterSeconds() + " s.", null, decision.retryAfterSeconds());
      return;
    }
    chain.doFilter(request, response);
  }

  static boolean isOrderWrite(String method, String path) {
    if (!ORDER_WRITE_PATH.matcher(path).matches() || "/api/v1/orders/preview".equals(path)) {
      return false;
    }
    if (path.endsWith("/close")) {
      return HttpMethod.POST.matches(method);
    }
    if ("/api/v1/orders".equals(path)) {
      return HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method);
    }
    return HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method);
  }

  private static String path(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && uri.startsWith(context)) {
      uri = uri.substring(context.length());
    }
    return uri;
  }
}
