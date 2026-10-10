package com.stocks.mcp;

import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.security.RequestMetas;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves {@code RequestMeta.mcpClient} for an MCP request (§20.1). STATELESS mode has no session and
 * {@code clientInfo} arrives only on {@code initialize}, so the name comes from, in order:
 * <ol>
 *   <li>the {@code X-Stocks-Client} request header (documented in {@code docs/agents.md});</li>
 *   <li>TODO(WS5, Phase 1): the {@code clientInfo.name/version} of the last {@code initialize} seen for the caller's
 *       {@code apiKeyId} (Caffeine, 24 h), plus the {@code AUTH}/{@code MCP_INITIALIZE} audit row;</li>
 *   <li>the {@code User-Agent}.</li>
 * </ol>
 * {@code RequestMeta}'s constructor clips the result to 120 characters and strips control characters.
 */
@Component
public class McpClientNames {

  /** The client-name header of §20.4. */
  public static final String HEADER = "X-Stocks-Client";

  /** The MCP client name of {@code request}, or null when the caller sent nothing usable. */
  public String resolve(HttpServletRequest request, PlayerPrincipal principalOrNull) {
    String header = RequestMetas.header(request, HEADER);
    if (StringUtils.hasText(header)) {
      return header.strip();
    }
    String userAgent = RequestMetas.header(request, HttpHeaders.USER_AGENT);
    return StringUtils.hasText(userAgent) ? userAgent.strip() : null;
  }
}
