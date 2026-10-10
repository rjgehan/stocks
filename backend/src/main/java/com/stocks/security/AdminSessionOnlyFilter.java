package com.stocks.security;

import com.stocks.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of the admin chain ({@code /api/v1/admin/**}, §17.1 chain 1): any request that carries an
 * {@code Authorization} header, whatever its scheme or value and even the admin's own API key, gets
 * <b>403 {@code ADMIN_REQUIRES_SESSION}</b> before authentication or CSRF run. Admin work is session-only (D14,
 * §17.4 layer 3). Not a Spring bean, so it never runs outside that chain.
 */
public final class AdminSessionOnlyFilter extends OncePerRequestFilter {

  private final ProblemWriter problems;

  public AdminSessionOnlyFilter(ProblemWriter problems) {
    this.problems = problems;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
      problems.write(request, response, ErrorCode.ADMIN_REQUIRES_SESSION,
          "Admin endpoints accept a browser session only; remove the Authorization header.");
      return;
    }
    chain.doFilter(request, response);
  }
}
