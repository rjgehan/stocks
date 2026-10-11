package com.stocks.oauth;

import com.stocks.common.time.TimeSource;
import com.stocks.contract.security.Scopes;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * {@code GET /oauth2/authorize} (§17.9.3 step 1): validates the request in the spec's order, stores it as a pending
 * request and sends the browser to the SPA consent page. Errors about the client or the redirect URI are never
 * redirected (RFC 6749 §4.1.2.1: an unverified redirect could be an open redirector); they go to the SPA error page.
 * Every later error goes back to the client's redirect URI with {@code state} and {@code iss} (RFC 9207).
 */
@Service
public class AuthorizationEndpoint {

  /** Where the browser goes next. */
  public sealed interface Outcome {
    /** A relative URL on this origin (the consent or error page) or the client's redirect URI. */
    String location();
  }

  /** The pending request was stored: show the consent page. */
  public record ToConsent(String requestId, String location) implements Outcome {}

  /** An error the client should see, at its redirect URI. */
  public record ToClient(String location) implements Outcome {}

  /** An error that must not be redirected: the SPA error page. */
  public record ToErrorPage(String error, String description, String location) implements Outcome {}

  static final int MAX_STATE = 2000;
  static final String CHALLENGE_METHOD = "S256";
  static final String OFFLINE_ACCESS = "offline_access";

  private final OAuthClients clients;
  private final OAuthStore store;
  private final OAuthUrls urls;
  private final OAuthIds ids;
  private final OAuthProperties properties;
  private final TimeSource time;

  public AuthorizationEndpoint(OAuthClients clients, OAuthStore store, OAuthUrls urls, OAuthIds ids,
                               OAuthProperties properties, TimeSource time) {
    this.clients = clients;
    this.store = store;
    this.urls = urls;
    this.ids = ids;
    this.properties = properties;
    this.time = time;
  }

  /**
   * Handles one authorization request.
   *
   * @param params the query parameters; a parameter sent more than once is an error (RFC 6749 §3.1)
   */
  public Outcome authorize(Map<String, String[]> params) {
    String clientId = single(params, "client_id");
    if (clientId == null) {
      return errorPage(OAuthException.INVALID_REQUEST, "client_id is missing or repeated.");
    }
    OAuthClient client;
    try {
      client = clients.resolve(clientId);
    } catch (OAuthException e) {
      return errorPage(OAuthException.INVALID_CLIENT, e.description());
    }
    if (repeated(params, "redirect_uri")) {
      return errorPage(OAuthException.INVALID_REQUEST, "redirect_uri is repeated.");
    }
    String requested = single(params, "redirect_uri");
    String redirectUri;
    if (requested == null) {
      if (client.redirectUris().size() != 1) {
        return errorPage(OAuthException.INVALID_REQUEST,
            "redirect_uri is required: the client registered more than one.");
      }
      redirectUri = client.redirectUris().getFirst();
    } else {
      redirectUri = client.redirectUris().stream().anyMatch(r -> RedirectUris.matches(r, requested)) ? requested
          : null;
      if (redirectUri == null) {
        return errorPage(OAuthException.INVALID_REQUEST,
            "redirect_uri does not match any redirect URI the client registered.");
      }
    }
    if (repeated(params, "state")) {
      return errorPage(OAuthException.INVALID_REQUEST, "state is repeated.");
    }
    String state = single(params, "state");
    if (state != null && state.length() > MAX_STATE) {
      return errorPage(OAuthException.INVALID_REQUEST, "state is longer than " + MAX_STATE + " characters.");
    }
    // From here on, errors are returned to the client.
    for (String name : new String[] {"response_type", "code_challenge", "code_challenge_method", "scope",
        "resource"}) {
      if (repeated(params, name)) {
        return toClient(redirectUri, OAuthException.INVALID_REQUEST, name + " is repeated.", state);
      }
    }
    String responseType = single(params, "response_type");
    if (!"code".equals(responseType)) {
      return toClient(redirectUri, OAuthException.UNSUPPORTED_RESPONSE_TYPE, "Only response_type=code is supported.",
          state);
    }
    String challenge = single(params, "code_challenge");
    if (challenge == null || !Secrets.PKCE.matcher(challenge).matches()) {
      return toClient(redirectUri, OAuthException.INVALID_REQUEST,
          "PKCE is required: code_challenge must be 43-128 characters of [A-Za-z0-9._~-].", state);
    }
    if (!CHALLENGE_METHOD.equals(single(params, "code_challenge_method"))) {
      return toClient(redirectUri, OAuthException.INVALID_REQUEST, "code_challenge_method must be S256.", state);
    }
    String resource = single(params, "resource");
    if (resource != null && !urls.isResource(resource)) {
      return toClient(redirectUri, OAuthException.INVALID_TARGET,
          "This server issues tokens for " + urls.resource() + " only.", state);
    }
    Set<String> scopes = new LinkedHashSet<>();
    boolean offline = false;
    String scope = single(params, "scope");
    if (scope != null) {
      for (String s : scope.trim().split("\\s+")) {
        String v = s.toLowerCase(Locale.ROOT);
        if (Scopes.ALL.contains(v)) {
          scopes.add(v);
        }
        offline |= OFFLINE_ACCESS.equals(v);
      }
    }
    if (scopes.isEmpty()) {
      scopes.addAll(Scopes.ALL);
    }
    Instant now = time.now();
    int cap = properties.maxPendingRequests();
    if (cap > 0 && store.livePendingRequests(now) >= cap) {
      // a flood of authorization requests (many addresses) must not grow the table without bound (§17.9.1)
      return toClient(redirectUri, OAuthException.TEMPORARILY_UNAVAILABLE,
          "Too many sign-ins are pending right now; try again in a few minutes.", state);
    }
    String requestId = ids.request();
    store.insertRequest(new OAuthStore.PendingRequest(requestId, client.clientId(), client.kind(), client.name(),
        client.host(), redirectUri, scopes, offline, client.allowsRefresh(), state, challenge, urls.resource(), now,
        now.plus(properties.requestTtl()), null));
    return new ToConsent(requestId, OAuthUrls.CONSENT_PAGE + "?request=" + enc(requestId));
  }

  /**
   * The client's redirect URI with {@code params} appended (it may already have a query) plus {@code iss}
   * (RFC 9207). Values are form-encoded.
   */
  public String redirect(String redirectUri, Map<String, String> params) {
    StringBuilder b = new StringBuilder(redirectUri);
    char sep = redirectUri.contains("?") ? '&' : '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (e.getValue() != null) {
        b.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        sep = '&';
      }
    }
    b.append(sep).append("iss=").append(enc(urls.issuer()));
    return b.toString();
  }

  private ToClient toClient(String redirectUri, String error, String description, String state) {
    Map<String, String> p = new java.util.LinkedHashMap<>();
    p.put("error", error);
    p.put("error_description", description);
    p.put("state", state);
    return new ToClient(redirect(redirectUri, p));
  }

  /** The SPA error page for an error that must not go to the client. */
  public static ToErrorPage errorPage(String error, String description) {
    return new ToErrorPage(error, description, OAuthUrls.ERROR_PAGE + "?error=" + enc(error)
        + "&error_description=" + enc(description));
  }

  private static String single(Map<String, String[]> params, String name) {
    String[] values = params.get(name);
    if (values == null || values.length != 1 || values[0] == null || values[0].isEmpty()) {
      return null;
    }
    return values[0];
  }

  private static boolean repeated(Map<String, String[]> params, String name) {
    String[] values = params.get(name);
    return values != null && values.length > 1;
  }

  static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
