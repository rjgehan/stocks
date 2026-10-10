package com.stocks.security;

import com.stocks.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * §17.5: failed authentications count against the caller IP's anonymous bucket (300/min), so a stream of random
 * {@code stk_} keys or session cookies is throttled like any other anonymous traffic. {@link BearerTokenFilter}
 * refuses a key from an exhausted IP before looking it up (no database work), and charges each bad key; the API
 * chain's entry point charges each anonymous 401. Over the limit the answer is 429 {@code RATE_LIMITED}. Not a bean.
 */
final class AuthFailureLimiter {

  private final RateLimitServiceImpl limits;
  private final ProblemWriter problems;

  AuthFailureLimiter(RateLimitServiceImpl limits, ProblemWriter problems) {
    this.limits = limits;
    this.problems = problems;
  }

  /** True (and a 429 written) when this IP has no anonymous budget left; nothing is charged. */
  boolean refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (limits == null || !limits.enabled()) {
      return false;
    }
    RateLimitServiceImpl.Decision d = limits.peekIp(ClientIp.resolve(request), RateLimitServiceImpl.IpClass.ANONYMOUS);
    if (d.allowed()) {
      return false;
    }
    write(request, response, d);
    return true;
  }

  /**
   * Charges one failed authentication; true (and a 429 written) when that exhausted the IP's budget, in which case
   * the caller must not write its own 401.
   */
  boolean charge(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (limits == null || !limits.enabled()) {
      return false;
    }
    RateLimitServiceImpl.Decision d = limits.consumeIp(ClientIp.resolve(request),
        RateLimitServiceImpl.IpClass.ANONYMOUS);
    if (d.allowed()) {
      return false;
    }
    write(request, response, d);
    return true;
  }

  private void write(HttpServletRequest request, HttpServletResponse response, RateLimitServiceImpl.Decision d)
      throws IOException {
    response.setHeader(RateLimitFilter.LIMIT, Long.toString(d.limit()));
    response.setHeader(RateLimitFilter.REMAINING, "0");
    response.setHeader(RateLimitFilter.RESET, Long.toString(d.resetSeconds()));
    problems.write(request, response, ErrorCode.RATE_LIMITED,
        "Too many failed authentications from this address; retry in " + d.retryAfterSeconds() + " s.", null,
        d.retryAfterSeconds());
  }
}
