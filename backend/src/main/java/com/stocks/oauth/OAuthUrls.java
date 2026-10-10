package com.stocks.oauth;

import com.stocks.config.StocksProperties;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The fixed URLs of the authorization server and the MCP resource (§17.9.1), all derived from
 * {@code stocks.public-url} ({@code APP_PUBLIC_URL}), never from the request's Host header: the issuer is that URL
 * without a trailing slash, the resource is {@code issuer + "/mcp"}.
 */
@Component
public class OAuthUrls {

  public static final String MCP_PATH = "/mcp";
  public static final String PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource";
  public static final String AUTHORIZATION_SERVER_PATH = "/.well-known/oauth-authorization-server";
  public static final String AUTHORIZE_PATH = "/oauth2/authorize";
  public static final String TOKEN_PATH = "/oauth2/token";
  public static final String REGISTER_PATH = "/oauth2/register";
  public static final String REVOKE_PATH = "/oauth2/revoke";
  public static final String JWKS_PATH = "/oauth2/jwks";
  /** The SPA consent page (relative, so the browser stays on whatever origin served /oauth2/authorize). */
  public static final String CONSENT_PAGE = "/oauth/consent";
  /** The SPA page for authorization errors that must not be redirected to the client (RFC 6749 §4.1.2.1). */
  public static final String ERROR_PAGE = "/oauth/error";

  private final String issuer;

  public OAuthUrls(StocksProperties properties) {
    this(properties.publicUrl());
  }

  OAuthUrls(String publicUrl) {
    String base = publicUrl == null ? "" : publicUrl.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    this.issuer = base;
  }

  /** {@code iss} of every token and of the RFC 9207 {@code iss} response parameter. */
  public String issuer() {
    return issuer;
  }

  /** The canonical MCP resource (RFC 8707 / RFC 9728 {@code resource}), and the access tokens' {@code aud}. */
  public String resource() {
    return issuer + MCP_PATH;
  }

  /** The RFC 9728 document for {@link #resource()} (the path-inserted form, what clients try first). */
  public String protectedResourceMetadata() {
    return issuer + PROTECTED_RESOURCE_PATH + MCP_PATH;
  }

  public String endpoint(String path) {
    return issuer + path;
  }

  /**
   * True when {@code candidate} names {@link #resource()}: scheme and host compared case-insensitively (MCP: servers
   * SHOULD accept uppercase scheme and host), one trailing slash ignored, no fragment allowed.
   */
  public boolean isResource(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return false;
    }
    try {
      URI given = URI.create(candidate.strip());
      URI ours = URI.create(resource());
      if (given.getRawFragment() != null || given.getScheme() == null || given.getHost() == null) {
        return false;
      }
      return given.getScheme().equalsIgnoreCase(ours.getScheme())
          && given.getHost().toLowerCase(Locale.ROOT).equals(ours.getHost().toLowerCase(Locale.ROOT))
          && effectivePort(given) == effectivePort(ours)
          && trimSlash(given.getRawPath()).equals(trimSlash(ours.getRawPath()))
          && given.getRawQuery() == null;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  private static String trimSlash(String path) {
    String p = path == null ? "" : path;
    return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
  }
}
