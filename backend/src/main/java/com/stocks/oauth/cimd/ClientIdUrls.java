package com.stocks.oauth.cimd;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Client-identifier URLs of Client ID Metadata Documents (CIMD draft-00 §3, §17.9.2): {@code https}, a host, no
 * userinfo, no fragment, a path without {@code .}/{@code ..} segments (also percent-encoded), the default port
 * (443) only, at most {@value #MAX_LENGTH} characters.
 */
public final class ClientIdUrls {

  public static final int MAX_LENGTH = 512;

  private ClientIdUrls() {}

  /** True when {@code clientId} looks like a URL (so it is treated as CIMD, never as a DCR id). */
  public static boolean looksLikeUrl(String clientId) {
    return clientId != null && clientId.regionMatches(true, 0, "https://", 0, 8)
        || clientId != null && clientId.regionMatches(true, 0, "http://", 0, 7);
  }

  /** The parsed URL, or empty with no reason when it breaks a rule (see {@link #problem}). */
  public static Optional<URI> parse(String clientId) {
    return problem(clientId) == null ? Optional.of(URI.create(clientId)) : Optional.empty();
  }

  /** Why {@code clientId} is not an acceptable client-identifier URL, or null when it is. */
  public static String problem(String clientId) {
    if (clientId == null || clientId.isEmpty() || clientId.length() > MAX_LENGTH) {
      return "the client_id URL is empty or longer than " + MAX_LENGTH + " characters";
    }
    URI uri;
    try {
      uri = new URI(clientId);
    } catch (Exception e) {
      return "the client_id is not a valid URL";
    }
    if (!"https".equals(uri.getScheme())) {
      return "the client_id URL must use https";
    }
    if (uri.getHost() == null || uri.getHost().isEmpty()) {
      return "the client_id URL has no host";
    }
    if (uri.getRawUserInfo() != null) {
      return "the client_id URL must not contain a username or password";
    }
    if (uri.getRawFragment() != null) {
      return "the client_id URL must not contain a fragment";
    }
    if (uri.getPort() != -1 && uri.getPort() != 443) {
      return "the client_id URL must use the default https port";
    }
    String path = uri.getRawPath();
    if (path == null || path.isEmpty()) {
      return "the client_id URL must contain a path";
    }
    for (String segment : path.split("/", -1)) {
      String s = segment.toLowerCase(Locale.ROOT).replace("%2e", ".");
      if (s.equals(".") || s.equals("..")) {
        return "the client_id URL must not contain dot segments";
      }
    }
    return null;
  }
}
