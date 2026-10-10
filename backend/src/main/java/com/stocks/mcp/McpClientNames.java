package com.stocks.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.web.Text;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.security.RequestMetas;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves {@code RequestMeta.mcpClient} for an MCP request (§20.1). STATELESS mode has no session and
 * {@code clientInfo} arrives only on {@code initialize}, so the name comes from, in order:
 * <ol>
 *   <li>the {@code X-Stocks-Client} request header (documented in {@code docs/agents.md});</li>
 *   <li>the {@code clientInfo.name/version} of the last {@code initialize} seen for the caller's {@code apiKeyId}
 *       (remembered {@link #REMEMBER}; captured by {@link McpInitializeCapture}, which also writes the
 *       {@code AUTH}/{@code MCP_INITIALIZE} audit row);</li>
 *   <li>the {@code User-Agent}.</li>
 * </ol>
 * {@code RequestMeta}'s constructor clips the result to 120 characters and strips control characters.
 */
@Component
public class McpClientNames {

  /** The client-name header of §20.4. */
  public static final String HEADER = "X-Stocks-Client";
  /** How long the client name of an {@code initialize} is remembered per API key (§20.1). */
  public static final Duration REMEMBER = Duration.ofHours(24);
  /** {@code RequestMeta.mcpClient} length (§6.6). */
  static final int MAX_CHARS = 120;
  private static final int MAX_KEYS = 10_000;

  private final Cache<Long, String> lastInitialize = Caffeine.newBuilder()
      .expireAfterWrite(REMEMBER)
      .maximumSize(MAX_KEYS)
      .build();

  /** The MCP client name of {@code request}, or null when the caller sent nothing usable. */
  public String resolve(HttpServletRequest request, PlayerPrincipal principalOrNull) {
    String header = RequestMetas.header(request, HEADER);
    if (StringUtils.hasText(header)) {
      return header.strip();
    }
    if (principalOrNull != null && principalOrNull.apiKeyId() != null) {
      String initialized = lastInitialize.getIfPresent(principalOrNull.apiKeyId());
      if (initialized != null) {
        return initialized;
      }
    }
    String userAgent = RequestMetas.header(request, HttpHeaders.USER_AGENT);
    return StringUtils.hasText(userAgent) ? userAgent.strip() : null;
  }

  /** Remembers the client name of an {@code initialize} made with an API key (browser sessions are not keyed). */
  public void rememberInitialize(PlayerPrincipal principal, String clientName) {
    if (principal != null && principal.apiKeyId() != null && StringUtils.hasText(clientName)) {
      lastInitialize.put(principal.apiKeyId(), clientName);
    }
  }

  /** The remembered name for an API key, if any. */
  String remembered(long apiKeyId) {
    return lastInitialize.getIfPresent(apiKeyId);
  }

  /**
   * {@code name/version} from {@code clientInfo} (just {@code name} without a version), control characters
   * stripped and clipped to {@value #MAX_CHARS}; null without a name.
   */
  static String clientName(String name, String version) {
    String n = Text.trimToNull(Text.stripControl(name));
    if (n == null) {
      return null;
    }
    String v = Text.trimToNull(Text.stripControl(version));
    return Text.clip(v == null ? n : n + "/" + v, MAX_CHARS);
  }
}
