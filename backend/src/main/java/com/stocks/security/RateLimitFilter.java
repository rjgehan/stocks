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
import org.springframework.web.util.UrlPathHelper;

/**
 * Chain 2's read limiter (§17.5), placed after authorization so only requests that would be served are charged:
 * <ul>
 *   <li>an authenticated player: one token of the player's read bucket, or of the heavy-read bucket for bars,
 *       portfolio and player history, the leaderboard and player profiles; web sessions and API keys/MCP have separate
 *       limits, each shared by all of the player's sessions or keys. Order writes ({@code POST /orders},
 *       {@code PATCH}/{@code DELETE /orders…}, position close) are <b>not</b> charged here: {@code OrderGate} charges
 *       the write bucket once per write, after its idempotency fast path, so replays stay free. Every {@code /mcp}
 *       call counts as one read; order tools additionally go through {@code OrderGate}.</li>
 *   <li>{@code POST /auth/login} 10/min and {@code POST /auth/signup} 5/hour per client IP, <b>whether or not the
 *       caller is signed in</b>;</li>
 *   <li>any other anonymous request, per client IP: 300/min. Failed authentications (a bad API key, an anonymous
 *       401) are charged to the same bucket by {@link AuthFailureLimiter}.</li>
 * </ul>
 * Requests are classified on the decoded, normalized path ({@link #path}), the one Security and MVC route by.
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
      "^/api/v1/(orders|orders/[^/]+|order-groups|order-groups/[^/]+|account/positions/[^/]+/close)$");

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
    RateLimitServiceImpl.IpClass credentials = credentialClass(request.getMethod(), path);
    if (credentials != null) {
      // login and signup are always per IP, signed in or not: a session must not buy 60x the login budget
      decision = limits.consumeIp(ClientIp.resolve(request), credentials);
    } else if (principal != null) {
      if (isOrderWrite(request.getMethod(), path)) {
        chain.doFilter(request, response);
        return;
      }
      RateLimitServiceImpl.ReadClass readClass = HttpMethod.GET.matches(request.getMethod())
          && HEAVY.matcher(path).matches() ? RateLimitServiceImpl.ReadClass.HEAVY : RateLimitServiceImpl.ReadClass.READ;
      decision = limits.consumeRead(principal.playerId(), principal.authMethod() != AuthMethod.SESSION, readClass);
    } else {
      decision = limits.consumeIp(ClientIp.resolve(request), RateLimitServiceImpl.IpClass.ANONYMOUS);
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
    if (!ORDER_WRITE_PATH.matcher(path).matches() || "/api/v1/orders/preview".equals(path)
        || "/api/v1/order-groups/preview".equals(path)) {
      return false;
    }
    if ("/api/v1/order-groups".equals(path)) {
      return HttpMethod.POST.matches(method);                 // a group order (§11.9): OrderGate charges it
    }
    if (path.endsWith("/close")) {
      return HttpMethod.POST.matches(method);
    }
    if ("/api/v1/orders".equals(path)) {
      return HttpMethod.POST.matches(method) || HttpMethod.DELETE.matches(method);
    }
    return HttpMethod.PATCH.matches(method) || HttpMethod.DELETE.matches(method);
  }

  /** {@code POST /auth/login} → LOGIN, {@code POST /auth/signup} → SIGNUP, else null. */
  static RateLimitServiceImpl.IpClass credentialClass(String method, String path) {
    if (!HttpMethod.POST.matches(method)) {
      return null;
    }
    return switch (path) {
      case "/api/v1/auth/login" -> RateLimitServiceImpl.IpClass.LOGIN;
      case "/api/v1/auth/signup" -> RateLimitServiceImpl.IpClass.SIGNUP;
      default -> null;
    };
  }

  /**
   * The path the request is routed by: percent-decoded, without {@code ;} parameters and with {@code //} collapsed,
   * within the application (as Spring Security and MVC match it). Classifying the raw URI let
   * {@code /api/v1/auth/logi%6E} log in on the 300/min anonymous budget instead of login's 10/min.
   */
  static String path(HttpServletRequest request) {
    return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
  }
}
