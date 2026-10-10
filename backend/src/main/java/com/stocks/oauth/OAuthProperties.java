package com.stocks.oauth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code stocks.oauth} (§17.9.7): the OAuth 2.1 authorization server for the claude.ai and ChatGPT connectors. Every
 * member has a safe default, so production boots with its current {@code .env}; the signing key is generated and
 * persisted when {@code OAUTH_JWK_PEM_FILE} is not set (§17.9.4).
 */
@ConfigurationProperties("stocks.oauth")
public record OAuthProperties(
    /* false: /.well-known and /oauth2 answer 404, JWTs are not accepted, the 401 challenge has no resource_metadata. */
    @DefaultValue("true") boolean enabled,
    /* OAUTH_JWK_PEM_FILE: an RSA private key (PKCS#8 or PKCS#1 PEM). Empty: generated on first boot, stored
       AES-GCM-encrypted under a key derived from API_KEY_PEPPER. */
    String jwkPemFile,
    @DefaultValue("30m") Duration accessTokenTtl,
    /* Lifetime of each refresh token from its issue; rotation makes it sliding for an active connector. */
    @DefaultValue("30d") Duration refreshTokenTtl,
    /* A rotated refresh token presented again within this window only fails (a client racing two refreshes);
       later it revokes the whole grant (reuse detection). */
    @DefaultValue("10s") Duration refreshReuseGrace,
    @DefaultValue("5m") Duration codeTtl,
    /* How long a pending /oauth2/authorize request waits for login and consent. */
    @DefaultValue("15m") Duration requestTtl,
    /* The chain-2 grant check is cached this long; every revocation evicts at once. 0 = no cache (test). */
    @DefaultValue("60s") Duration grantCacheTtl,
    /* Loopback redirects (http://localhost, 127.0.0.1, [::1]) on any port: Claude Code, the MCP Inspector. */
    @DefaultValue("true") boolean allowLoopbackRedirects,
    /* Exact redirect URIs; a trailing "*" matches one path segment of [A-Za-z0-9_-]. */
    @DefaultValue({"https://claude.ai/api/mcp/auth_callback",
        "https://chatgpt.com/connector_platform_oauth_redirect",
        "https://chatgpt.com/connector/oauth/*"}) List<String> redirectAllowlist,
    /* Per client IP and minute: /oauth2/authorize and /oauth2/token (each). */
    @DefaultValue("60") int requestsPerMinPerIp,
    @DefaultValue Cimd cimd,
    @DefaultValue Dcr dcr) {

  /** Client ID Metadata Documents (§17.9.2): fetched SSRF-safely and cached in memory. */
  public record Cimd(
      @DefaultValue("true") boolean enabled,
      /* Connect + read deadline of one fetch. */
      @DefaultValue("3s") Duration timeout,
      @DefaultValue("8192") int maxBytes,
      @DefaultValue("5m") Duration minCache,
      @DefaultValue("24h") Duration maxCache) {}

  /** Dynamic Client Registration (RFC 7591; deprecated by MCP but still Claude's and ChatGPT's fallback). */
  public record Dcr(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("500") int maxClients,
      @DefaultValue("20") int perIpPerHour,
      /* A DCR client that never completed a grant is deleted after this. */
      @DefaultValue("24h") Duration unusedClientTtl,
      /* A DCR client without a live grant and unused this long is deleted. */
      @DefaultValue("90d") Duration idleClientTtl) {}
}
