package com.stocks.push;

import com.stocks.common.error.ApiException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which push endpoints this server will POST to (§19.2): the SSRF guard. An endpoint must be an absolute
 * {@code https} URL on port 443, at most {@value #MAX_LENGTH} characters, without user info or fragment, whose host is
 * a DNS name (never an IP literal) on the allowlist ({@code stocks.push.allowed-endpoint-hosts}; {@code *.example.com}
 * matches any subdomain of {@code example.com}, not the bare domain). {@code allow-insecure-endpoints} (tests only)
 * accepts http and any host and port.
 */
public final class EndpointPolicy {

  public static final int MAX_LENGTH = 1024;
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
  private static final Pattern DNS = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,62})(\\.[a-z0-9]([a-z0-9-]{0,62}))*$");

  private final List<String> allowed;
  private final boolean insecure;

  public EndpointPolicy(List<String> allowedHosts, boolean allowInsecure) {
    this.allowed = allowedHosts == null ? List.of()
        : allowedHosts.stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
    this.insecure = allowInsecure;
  }

  public static EndpointPolicy of(PushProperties props) {
    return new EndpointPolicy(props.allowedEndpointHosts(), props.allowInsecureEndpoints());
  }

  /** The endpoint as a URI; 400 {@code VALIDATION_FAILED} (field {@code endpoint}) when it is not allowed. */
  public URI require(String endpoint) {
    return check(endpoint).orElseThrow(() -> ApiException.validation("endpoint",
        "This push endpoint is not accepted: it must be an https URL of a known browser push service."));
  }

  /** The endpoint as a URI, or empty when it is not allowed. */
  public Optional<URI> check(String endpoint) {
    if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
      return Optional.empty();
    }
    URI uri;
    try {
      uri = new URI(endpoint.strip());
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    if (!uri.isAbsolute() || uri.isOpaque() || host.isEmpty() || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null) {
      return Optional.empty();
    }
    if (insecure) {
      return "https".equals(scheme) || "http".equals(scheme) ? Optional.of(uri) : Optional.empty();
    }
    if (!"https".equals(scheme) || !(uri.getPort() == -1 || uri.getPort() == 443)) {
      return Optional.empty();
    }
    if (host.startsWith("[") || IPV4.matcher(host).matches() || !DNS.matcher(host).matches()) {
      return Optional.empty();
    }
    return allowedHost(host) ? Optional.of(uri) : Optional.empty();
  }

  boolean allowedHost(String host) {
    for (String a : allowed) {
      if (a.startsWith("*.")) {
        String suffix = a.substring(1);                     // ".push.apple.com"
        if (host.endsWith(suffix) && host.length() > suffix.length()) {
          return true;
        }
      } else if (a.equals(host)) {
        return true;
      }
    }
    return false;
  }

  /** The push service host shown in the device list ({@code fcm.googleapis.com}). */
  public static String serviceHost(String endpoint) {
    try {
      String host = new URI(endpoint).getHost();
      return host == null ? "" : host.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException | RuntimeException e) {
      return "";
    }
  }
}
