package com.stocks.contract.security;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The current player (§5.3). REST and STOMP read Spring Security's {@code SecurityContextHolder}; MCP tools read the
 * {@link McpTransportContext} that WS5's transport {@code contextExtractor} fills (§20.1), because a STATELESS tool
 * may run off the servlet thread. Every MCP tool passes its {@code ctx} to the overloads below.
 *
 * <p>The principal is recognised when {@code Authentication.getPrincipal()} is a {@link PlayerPrincipal} and the
 * authentication is authenticated (WS1's authentication providers build it that way).
 */
public final class CurrentPlayer {

  /** {@link McpTransportContext} key of the {@link PlayerPrincipal} (set by {@code McpConfig}'s extractor). */
  public static final String PRINCIPAL_KEY = "stocks.principal";
  /** {@link McpTransportContext} key of the {@link RequestMeta} (set by {@code McpConfig}'s extractor). */
  public static final String REQUEST_META_KEY = "stocks.requestMeta";

  private CurrentPlayer() {}

  /** The authenticated player from {@code SecurityContextHolder}; 401 {@code UNAUTHENTICATED} if there is none. */
  public static PlayerPrincipal require() {
    return current().orElseThrow(CurrentPlayer::unauthenticated);
  }

  /** The authenticated player from {@code SecurityContextHolder}, if any. */
  public static Optional<PlayerPrincipal> current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof PlayerPrincipal p) {
      return Optional.of(p);
    }
    return Optional.empty();
  }

  /**
   * The player of an MCP call: the {@link McpTransportContext} entry first, then {@code SecurityContextHolder};
   * 401 {@code UNAUTHENTICATED} if neither has one.
   */
  public static PlayerPrincipal require(McpTransportContext ctx) {
    return current(ctx).orElseThrow(CurrentPlayer::unauthenticated);
  }

  /** The player of an MCP call, if any (context entry first, then {@code SecurityContextHolder}). */
  public static Optional<PlayerPrincipal> current(McpTransportContext ctx) {
    if (ctx != null && ctx.get(PRINCIPAL_KEY) instanceof PlayerPrincipal p) {
      return Optional.of(p);
    }
    return current();
  }

  /** The {@link RequestMeta} the MCP transport recorded for this call, if any. */
  public static Optional<RequestMeta> requestMeta(McpTransportContext ctx) {
    if (ctx != null && ctx.get(REQUEST_META_KEY) instanceof RequestMeta m) {
      return Optional.of(m);
    }
    return Optional.empty();
  }

  /** Builds the context the MCP {@code contextExtractor} returns (§20.1). Either argument may be null. */
  public static McpTransportContext mcpContext(PlayerPrincipal principal, RequestMeta meta) {
    Map<String, Object> values = new HashMap<>();
    if (principal != null) {
      values.put(PRINCIPAL_KEY, principal);
    }
    if (meta != null) {
      values.put(REQUEST_META_KEY, meta);
    }
    return McpTransportContext.create(values);
  }

  private static ApiException unauthenticated() {
    return ApiException.of(ErrorCode.UNAUTHENTICATED, "Authentication required.");
  }
}
