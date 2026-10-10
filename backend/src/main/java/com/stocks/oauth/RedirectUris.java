package com.stocks.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The redirect-URI policy of §17.9.2: a client may only register URIs on {@code stocks.oauth.redirect-allowlist}
 * (claude.ai's and ChatGPT's callbacks) or loopback URIs, so an authorization code can only travel to claude.ai,
 * chatgpt.com or the user's own machine. At authorization time the requested URI must equal a registered one, except
 * that loopback URIs match with the port ignored (RFC 8252 §7.3; Claude Code registers {@code http://localhost/callback}
 * and uses an ephemeral port).
 */
@Component
public class RedirectUris {

  static final int MAX_LENGTH = 1000;
  private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
  private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

  private final List<String> exact = new ArrayList<>();
  private final List<String> prefixes = new ArrayList<>();
  private final boolean loopback;

  public RedirectUris(OAuthProperties properties) {
    this(properties.redirectAllowlist(), properties.allowLoopbackRedirects());
  }

  RedirectUris(List<String> allowlist, boolean allowLoopback) {
    for (String entry : allowlist == null ? List.<String>of() : allowlist) {
      String e = entry == null ? "" : entry.strip();
      if (e.isEmpty()) {
        continue;
      }
      if (e.endsWith("*")) {
        prefixes.add(e.substring(0, e.length() - 1));
      } else {
        exact.add(e);
      }
    }
    this.loopback = allowLoopback;
  }

  /** True when {@code uri} is well formed and on the allowlist (or an allowed loopback URI). */
  public boolean isAllowed(String uri) {
    URI parsed = parse(uri);
    if (parsed == null) {
      return false;
    }
    if (exact.contains(uri)) {
      return true;
    }
    for (String prefix : prefixes) {
      if (uri.startsWith(prefix) && SEGMENT.matcher(uri.substring(prefix.length())).matches()) {
        return true;
      }
    }
    return loopback && isLoopback(parsed);
  }

  /**
   * True when the requested URI may be used for a client that registered {@code registered}: identical strings, or
   * two loopback URIs that differ only in the port.
   */
  public static boolean matches(String registered, String requested) {
    if (registered == null || requested == null) {
      return false;
    }
    if (registered.equals(requested)) {
      return true;
    }
    URI r = parse(registered);
    URI q = parse(requested);
    if (r == null || q == null || !isLoopback(r) || !isLoopback(q)) {
      return false;
    }
    return r.getScheme().equalsIgnoreCase(q.getScheme())
        && r.getHost().equalsIgnoreCase(q.getHost())
        && nullToEmpty(r.getRawPath()).equals(nullToEmpty(q.getRawPath()))
        && nullToEmpty(r.getRawQuery()).equals(nullToEmpty(q.getRawQuery()));
  }

  /** True for {@code http(s)://localhost|127.0.0.1|[::1][:port]/…}. */
  public static boolean isLoopback(String uri) {
    URI parsed = parse(uri);
    return parsed != null && isLoopback(parsed);
  }

  /** The host to show on the consent screen (MCP: the redirect URI hostname MUST be displayed). */
  public static String host(String uri) {
    URI parsed = parse(uri);
    return parsed == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
  }

  static boolean isLoopback(URI uri) {
    return uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
        && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
  }

  /**
   * An absolute http(s) URI with a host, no userinfo and no fragment, at most {@value #MAX_LENGTH} characters; http
   * only for loopback hosts (MCP: redirect URIs MUST be localhost or https). Null otherwise.
   */
  static URI parse(String uri) {
    if (uri == null || uri.isEmpty() || uri.length() > MAX_LENGTH || !uri.equals(uri.strip())) {
      return null;
    }
    try {
      URI u = new URI(uri);
      if (!u.isAbsolute() || u.getHost() == null || u.getRawUserInfo() != null || u.getRawFragment() != null) {
        return null;
      }
      String scheme = u.getScheme().toLowerCase(Locale.ROOT);
      if (scheme.equals("https")) {
        return u;
      }
      if (scheme.equals("http") && LOOPBACK_HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))) {
        return u;
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
