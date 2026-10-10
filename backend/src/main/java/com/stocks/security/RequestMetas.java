package com.stocks.security;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.web.RequestIds;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.firewall.RequestRejectedException;

/**
 * Builds the {@link RequestMeta} of an HTTP request (§6.6): request id, channel, client IP (after the trusted-proxy
 * valve), User-Agent. {@code RequestMeta}'s constructor bounds the client-controlled strings. WS5's MCP transport sets
 * {@code mcpClient}/{@code mcpTool} itself.
 */
public final class RequestMetas {

  private RequestMetas() {}

  /** Metadata for {@code request}, with the channel derived from {@code principal} (null = anonymous). */
  public static RequestMeta of(HttpServletRequest request, PlayerPrincipal principal) {
    return new RequestMeta(RequestIds.of(request), channel(request, principal), ClientIp.resolve(request),
        header(request, HttpHeaders.USER_AGENT), null, null);
  }

  /** A header value, or null when Spring Security's firewall rejects it (e.g. control characters). */
  public static String header(HttpServletRequest request, String name) {
    try {
      return request.getHeader(name);
    } catch (RequestRejectedException rejected) {
      return null;
    }
  }

  /**
   * {@code ADMIN} on the admin API; {@code MCP_KEY}/{@code REST_KEY} for an API key on {@code /mcp} or REST
   * ({@code MCP_OAUTH} in Phase 2); {@code WEB} otherwise (sessions and anonymous callers).
   */
  public static Channel channel(HttpServletRequest request, PlayerPrincipal principal) {
    String path = request.getRequestURI();
    if (path != null && path.startsWith("/api/v1/admin/")) {
      return Channel.ADMIN;
    }
    if (principal != null && principal.authMethod() == AuthMethod.API_KEY) {
      return path != null && (path.equals("/mcp") || path.startsWith("/mcp/")) ? Channel.MCP_KEY : Channel.REST_KEY;
    }
    if (principal != null && principal.authMethod() == AuthMethod.OAUTH) {
      return Channel.MCP_OAUTH;
    }
    return Channel.WEB;
  }
}
