package com.stocks.oauth.cimd;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.common.web.Text;
import com.stocks.oauth.OAuthClient;
import com.stocks.oauth.OAuthException;
import com.stocks.oauth.OAuthProperties;
import com.stocks.oauth.RedirectUris;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client ID Metadata Document clients (§17.9.2, CIMD draft-00): the {@code client_id} is an HTTPS URL whose JSON
 * document is fetched SSRF-safely ({@link CimdFetcher}) on first use and validated:
 * <ul>
 *   <li>a JSON object whose {@code client_id} equals the URL exactly;</li>
 *   <li>{@code redirect_uris}: a non-empty array; only URIs on the server's allowlist are kept, and none left is an
 *       error;</li>
 *   <li>{@code token_endpoint_auth_method}: any {@code client_secret*} method or a {@code client_secret} member is
 *       refused (CIMD has no shared secret); anything else ({@code none}, ChatGPT's {@code private_key_jwt}, an
 *       unknown method, absent) makes a public client: {@code private_key_jwt} is not advertised, so clients
 *       authenticate with {@code none} and an assertion is ignored;</li>
 *   <li>{@code grant_types}: absent or empty (both are assumed) or containing {@code authorization_code}; unknown
 *       types (Claude's {@code jwt-bearer}) are ignored; {@code response_types}, when non-empty, contains
 *       {@code code}; every other member ({@code jwks_uri}, {@code logo_uri}, ...) is ignored;</li>
 *   <li>{@code client_name}: optional (the host is shown instead), control characters stripped, 100 characters.</li>
 * </ul>
 * Valid documents are cached in memory (≤ 500) for the response's {@code max-age} clamped to
 * [{@code min-cache}, {@code max-cache}]; {@code no-store} is not cached, and errors are never cached.
 */
public class CimdClients {

  private static final Logger log = LoggerFactory.getLogger(CimdClients.class);
  private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*\"?(\\d{1,10})\"?");
  static final int MAX_NAME = 100;

  private record Entry(OAuthClient client, Instant expiresAt) {}

  private final CimdFetcher fetcher;
  private final JsonMapper jsonMapper;
  private final RedirectUris redirects;
  private final OAuthProperties.Cimd settings;
  private final TimeSource time;
  private final Cache<String, Entry> cache;

  public CimdClients(CimdFetcher fetcher, JsonMapper jsonMapper, RedirectUris redirects, OAuthProperties properties,
                     TimeSource time) {
    this.fetcher = fetcher;
    this.jsonMapper = jsonMapper;
    this.redirects = redirects;
    this.settings = properties.cimd();
    this.time = time;
    this.cache = Caffeine.newBuilder().maximumSize(500).expireAfterWrite(settings.maxCache()).build();
  }

  /**
   * The client named by {@code clientIdUrl}, from the cache or fetched now.
   *
   * @throws OAuthException {@code invalid_client} when the URL breaks the rules, the fetch fails or the document is
   *                        invalid (the description is safe to show)
   */
  public OAuthClient resolve(String clientIdUrl) {
    if (!settings.enabled()) {
      throw new OAuthException(OAuthException.INVALID_CLIENT,
          "Client ID metadata documents are not accepted by this server.");
    }
    String problem = ClientIdUrls.problem(clientIdUrl);
    if (problem != null) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, problem);
    }
    Instant now = time.now();
    Entry cached = cache.getIfPresent(clientIdUrl);
    if (cached != null && cached.expiresAt().isAfter(now)) {
      return cached.client();
    }
    URI url = URI.create(clientIdUrl);
    CimdFetcher.Response response;
    try {
      response = fetcher.fetch(url);
    } catch (CimdFetcher.FetchException e) {
      log.info("CIMD fetch of {} failed: {}", clientIdUrl, e.getMessage());
      throw new OAuthException(OAuthException.INVALID_CLIENT, e.getMessage());
    }
    OAuthClient client = parse(clientIdUrl, url.getHost().toLowerCase(Locale.ROOT), response.body());
    Duration ttl = cacheTtl(response.cacheControl());
    if (ttl != null) {
      cache.put(clientIdUrl, new Entry(client, now.plus(ttl)));
    } else {
      cache.invalidate(clientIdUrl);
    }
    return client;
  }

  /** Drops every cached document (tests). */
  public void clear() {
    cache.invalidateAll();
  }

  /** How long a document may be cached; null = not at all ({@code no-store}). */
  Duration cacheTtl(String cacheControl) {
    if (cacheControl == null || cacheControl.isBlank()) {
      return settings.minCache();
    }
    String cc = cacheControl.toLowerCase(Locale.ROOT);
    if (cc.contains("no-store")) {
      return null;
    }
    Matcher m = MAX_AGE.matcher(cc);
    if (!m.find()) {
      return settings.minCache();
    }
    Duration maxAge = Duration.ofSeconds(Long.parseLong(m.group(1)));
    if (maxAge.compareTo(settings.minCache()) < 0) {
      return settings.minCache();
    }
    return maxAge.compareTo(settings.maxCache()) > 0 ? settings.maxCache() : maxAge;
  }

  OAuthClient parse(String clientIdUrl, String host, byte[] body) {
    JsonNode doc;
    try {
      doc = jsonMapper.readTree(body);
    } catch (JacksonException e) {
      throw invalid("The client metadata document is not valid JSON.");
    }
    if (doc == null || !doc.isObject()) {
      throw invalid("The client metadata document is not a JSON object.");
    }
    JsonNode id = doc.get("client_id");
    if (id == null || !id.isString() || !clientIdUrl.equals(id.asString())) {
      throw invalid("The client metadata document's client_id does not match its URL.");
    }
    if (doc.has("client_secret") || doc.has("client_secret_expires_at")) {
      throw invalid("A client metadata document must not contain a client secret.");
    }
    JsonNode method = doc.get("token_endpoint_auth_method");
    if (method != null && method.isString() && method.asString().startsWith("client_secret")) {
      throw invalid("A client metadata document cannot use a client secret ('" + Text.clip(method.asString(), 40)
          + "'); use none.");
    }
    JsonNode uris = doc.get("redirect_uris");
    if (uris == null || !uris.isArray() || uris.isEmpty()) {
      throw invalid("The client metadata document has no redirect_uris.");
    }
    List<String> allowed = new ArrayList<>();
    for (JsonNode u : uris) {
      if (u.isString() && redirects.isAllowed(u.asString()) && !allowed.contains(u.asString())) {
        allowed.add(u.asString());
      }
    }
    if (allowed.isEmpty()) {
      throw invalid("None of the client's redirect_uris is allowed by this server.");
    }
    Set<String> grants = new LinkedHashSet<>();
    JsonNode grantTypes = doc.get("grant_types");
    if (grantTypes == null || grantTypes.isNull() || grantTypes.isArray() && grantTypes.isEmpty()) {
      grants.add(OAuthClient.GRANT_CODE);
      grants.add(OAuthClient.GRANT_REFRESH);
    } else if (grantTypes.isArray()) {
      for (JsonNode g : grantTypes) {
        if (g.isString() && (g.asString().equals(OAuthClient.GRANT_CODE)
            || g.asString().equals(OAuthClient.GRANT_REFRESH))) {
          grants.add(g.asString());
        }
      }
    }
    if (!grants.contains(OAuthClient.GRANT_CODE)) {
      throw invalid("The client metadata document does not allow the authorization_code grant.");
    }
    JsonNode responseTypes = doc.get("response_types");
    if (responseTypes != null && !responseTypes.isNull() && !(responseTypes.isArray() && responseTypes.isEmpty())) {
      boolean code = false;
      if (responseTypes.isArray()) {
        for (JsonNode r : responseTypes) {
          code |= r.isString() && r.asString().equals("code");
        }
      }
      if (!code) {
        throw invalid("The client metadata document does not allow response_type code.");
      }
    }
    JsonNode nameNode = doc.get("client_name");
    String name = nameNode != null && nameNode.isString()
        ? Text.trimToNull(Text.clip(Text.stripControl(nameNode.asString()), MAX_NAME)) : null;
    return new OAuthClient(clientIdUrl, OAuthClientKind.CIMD, name == null ? host : name, host, allowed, grants,
        OAuthClient.AUTH_NONE, null);
  }

  private static OAuthException invalid(String description) {
    return new OAuthException(OAuthException.INVALID_CLIENT, description);
  }
}
