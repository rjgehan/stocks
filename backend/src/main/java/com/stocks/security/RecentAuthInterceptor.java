package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces {@link RecentAuth}: the session's {@code authenticatedAt} must be within
 * {@code stocks.security.recent-auth} of {@link TimeSource#now()}; an API key never qualifies (R routes are
 * session-only anyway). Thrown as {@link ApiException}, so {@code ProblemDetailsAdvice} renders the §6.5 problem.
 */
public final class RecentAuthInterceptor implements HandlerInterceptor {

  private final TimeSource time;
  private final Duration window;

  public RecentAuthInterceptor(TimeSource time, StocksProperties properties) {
    this.time = time;
    this.window = properties.security().recentAuth();
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method && requiresRecentAuth(method)) {
      check(CurrentPlayer.require());
    }
    return true;
  }

  static boolean requiresRecentAuth(HandlerMethod method) {
    return method.hasMethodAnnotation(RecentAuth.class)
        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RecentAuth.class);
  }

  /** @throws ApiException 403 {@code RECENT_AUTH_REQUIRED} unless {@code p} re-entered the password recently */
  public void check(PlayerPrincipal p) {
    Instant at = p.authenticatedAt();
    if (p.authMethod() != AuthMethod.SESSION || at == null || at.plus(window).isBefore(time.now())) {
      throw ApiException.of(ErrorCode.RECENT_AUTH_REQUIRED,
              "This action needs a recent password check (within " + window.toMinutes() + " minutes).")
          .withHint("POST /api/v1/auth/reauth {\"password\": \"...\"}, then retry.");
    }
  }
}
