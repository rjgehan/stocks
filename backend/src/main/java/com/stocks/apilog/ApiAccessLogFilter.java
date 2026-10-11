package com.stocks.apilog;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.security.PlayerPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Chain 2's API access log (§17.10), right after {@code BearerTokenFilter}: every REST request authenticated with an
 * API key is counted, after the rest of the chain answered it, so the 403 {@code FORBIDDEN_SCOPE} and 429
 * {@code RATE_LIMITED} answers of later filters are recorded too. The {@code /mcp} envelope is not (each MCP
 * {@code tools/call} is recorded by the tool handler with its own result code), and neither are browser sessions,
 * anonymous requests, bodies or query strings.
 *
 * <p>The endpoint is the MVC route template ({@code GET /api/v1/orders/{orderId}}); a request that never reached MVC
 * gets its path with public ids replaced by {@code {id}} and other variable-looking segments by {@code {value}}
 * ({@link #endpoint}). The outcome is the HTTP status. Not a Spring bean (a {@code Filter} bean would also be
 * registered on the servlet container).
 */
public final class ApiAccessLogFilter extends OncePerRequestFilter {

  /** {@code ord_01J…}: a §6.1 public id. */
  private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z]{3}_[0-9A-HJKMNP-TV-Z]{26}$");
  /** A fixed route word: lower-case letters and dashes, or a version ({@code v1}). */
  private static final Pattern WORD = Pattern.compile("^([a-z][a-z-]*|v[0-9]+)$");

  private final ApiAccessRecorder recorder;

  public ApiAccessLogFilter(ApiAccessRecorder recorder) {
    this.recorder = recorder;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    return path.equals("/mcp") || path.startsWith("/mcp/") || !hasBearer(request);
  }

  private static boolean hasBearer(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    PlayerPrincipal caller = keyPrincipal();
    long start = System.nanoTime();
    int status = 500;
    try {
      chain.doFilter(request, response);
      status = response.getStatus();
    } finally {
      if (caller != null) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        recorder.record(caller, Channel.REST_KEY, endpoint(request), Integer.toString(status), ms,
            request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
      }
    }
  }

  /** The API-key principal {@code BearerTokenFilter} set, if any. */
  private static PlayerPrincipal keyPrincipal() {
    Authentication auth = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
      return null;
    }
    return auth.getPrincipal() instanceof PlayerPrincipal p && p.authMethod() == AuthMethod.API_KEY ? p : null;
  }

  /** {@code GET /api/v1/orders/{orderId}}: the MVC pattern, else the normalized path. */
  static String endpoint(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String path = pattern instanceof String p && !p.isBlank() ? p
        : normalize(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    return request.getMethod() + " " + path;
  }

  /** Public ids → {@code {id}}, segments that are not route words (symbols, handles with digits, …) → {@code {value}}. */
  static String normalize(String path) {
    if (path == null || path.isEmpty()) {
      return "/";
    }
    StringBuilder out = new StringBuilder();
    for (String segment : path.split("/")) {
      if (segment.isEmpty()) {
        continue;
      }
      out.append('/');
      if (PUBLIC_ID.matcher(segment).matches()) {
        out.append("{id}");
      } else if (WORD.matcher(segment).matches() && segment.length() <= 40) {
        out.append(segment);
      } else {
        out.append("{value}");
      }
    }
    return out.isEmpty() ? "/" : out.toString();
  }
}
