package com.stocks.common.error;

/**
 * The {@code WWW-Authenticate} challenge of chain-2 401s (§17.1, §17.9.5): {@code Bearer realm="stocks"}, plus
 * {@code resource_metadata="${public-url}/.well-known/oauth-protected-resource/mcp"} while the OAuth server is on,
 * which is how the claude.ai and ChatGPT connectors discover it (RFC 9728 §5.1). {@code security.SecurityConfig}
 * publishes the bean; {@link ProblemDetailsAdvice} and the security layer's own 401s use the same value.
 */
public record BearerChallenge(String value) {

  /** Without OAuth (Phase 1, {@code stocks.oauth.enabled=false}). */
  public static final BearerChallenge DEFAULT = new BearerChallenge(ProblemDetailsAdvice.BEARER_CHALLENGE);

  /** {@code resourceMetadataUrl} null or blank: {@link #DEFAULT}. */
  public static BearerChallenge of(String resourceMetadataUrl) {
    if (resourceMetadataUrl == null || resourceMetadataUrl.isBlank()) {
      return DEFAULT;
    }
    return new BearerChallenge(DEFAULT.value() + ", resource_metadata=\"" + resourceMetadataUrl.replace("\"", "%22")
        + "\"");
  }

  /** The challenge for a rejected token (RFC 6750 §3.1). */
  public String invalidToken() {
    return value + ", error=\"invalid_token\"";
  }
}
