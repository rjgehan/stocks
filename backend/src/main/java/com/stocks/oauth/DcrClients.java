package com.stocks.oauth;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.common.web.Text;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dynamic Client Registration (RFC 7591; §17.9.2): {@code POST /oauth2/register} creates an {@code oauth_client}
 * row. Deprecated by MCP but still the fallback of Claude and ChatGPT when CIMD is unavailable, so it stays on with
 * limits: a body of at most 16 KiB (the controller), {@code client_name} 1–100 printable characters, at most 10
 * {@code redirect_uris}, every one on the redirect allowlist, {@code grant_types} ⊆ {@code authorization_code
 * refresh_token}, {@code response_types} ⊆ {@code code}, {@code stocks.oauth.dcr.per-ip-per-hour} registrations per
 * IP and at most {@code stocks.oauth.dcr.max-clients} live registrations. Confidential clients get a 43-character
 * secret, stored as SHA-256. {@link OAuthCleanupTask} deletes registrations that never completed a grant and idle
 * ones.
 */
@Service
public class DcrClients {

  static final int MAX_REDIRECT_URIS = 10;
  static final int MAX_NAME = 100;
  static final String DEFAULT_NAME = "MCP client";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final JsonMapper jsonMapper;
  private final OAuthProperties properties;
  private final RedirectUris redirects;
  private final OAuthIds ids;

  public DcrClients(JdbcClient jdbc, TimeSource time, JsonMapper jsonMapper, OAuthProperties properties,
                    RedirectUris redirects, OAuthIds ids) {
    this.jdbc = jdbc;
    this.time = time;
    this.jsonMapper = jsonMapper;
    this.properties = properties;
    this.redirects = redirects;
    this.ids = ids;
  }

  /** A new registration; {@code secret} is the only copy of a confidential client's secret (null for public). */
  public record Registered(OAuthClient client, String secret, Instant issuedAt) {}

  /**
   * Validates and stores a registration request.
   *
   * @throws OAuthException {@code invalid_client_metadata}, {@code invalid_redirect_uri} (400) or
   *                        {@code temporarily_unavailable} (503, too many live clients)
   */
  public Registered register(JsonNode request, String ip, String userAgent) {
    if (request == null || !request.isObject()) {
      throw metadata("The registration request must be a JSON object.");
    }
    List<String> uris = redirectUris(request.get("redirect_uris"));
    Set<String> grants = grantTypes(request.get("grant_types"));
    responseTypes(request.get("response_types"));
    String method = authMethod(request.get("token_endpoint_auth_method"));
    String name = name(request.get("client_name"));
    Integer live = jdbc.sql("SELECT COUNT(*) FROM oauth_client").query(Integer.class).single();
    if (live >= properties.dcr().maxClients()) {
      throw new OAuthException(OAuthException.TEMPORARILY_UNAVAILABLE,
          "Too many registered clients; use a client ID metadata document (CIMD) instead.");
    }
    String clientId = ids.client();
    String secret = OAuthClient.AUTH_NONE.equals(method) ? null : Secrets.random();
    Instant now = time.now();
    jdbc.sql("""
            INSERT INTO oauth_client (client_id, client_name, redirect_uris, grant_types, auth_method, secret_hash,
                                      registered_ip, user_agent, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(clientId, name, jsonMapper.writeValueAsString(uris), String.join(" ", grants), method,
            secret == null ? null : Secrets.sha256Hex(secret), Text.clip(ip, 45), Text.clip(userAgent, 300),
            Jdbc.ts(now))
        .update();
    return new Registered(new OAuthClient(clientId, OAuthClientKind.DCR, name, "", uris, grants, method,
        secret == null ? null : Secrets.sha256Hex(secret)), secret, now);
  }

  /** The RFC 7591 §3.2.1 response body. */
  public Map<String, Object> response(Registered r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("client_id", r.client().clientId());
    out.put("client_id_issued_at", r.issuedAt().getEpochSecond());
    if (r.secret() != null) {
      out.put("client_secret", r.secret());
      out.put("client_secret_expires_at", 0);
    }
    out.put("client_name", r.client().name());
    out.put("redirect_uris", r.client().redirectUris());
    out.put("grant_types", new ArrayList<>(r.client().grantTypes()));
    out.put("response_types", List.of("code"));
    out.put("token_endpoint_auth_method", r.client().authMethod());
    return out;
  }

  public Optional<OAuthClient> find(String clientId) {
    return jdbc.sql("""
            SELECT client_id, client_name, redirect_uris, grant_types, auth_method, secret_hash
              FROM oauth_client WHERE client_id = ?""")
        .param(clientId)
        .query((rs, n) -> new OAuthClient(rs.getString("client_id"), OAuthClientKind.DCR,
            rs.getString("client_name"), "", parseUris(rs.getString("redirect_uris")),
            Set.of(rs.getString("grant_types").trim().split("\\s+")), rs.getString("auth_method"),
            rs.getString("secret_hash")))
        .optional();
  }

  /** Marks the registration as used (a grant was created or a token issued). */
  public void touch(String clientId) {
    jdbc.sql("UPDATE oauth_client SET last_used_at = ? WHERE client_id = ?")
        .params(Jdbc.ts(time.now()), clientId).update();
  }

  private List<String> parseUris(String json) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : jsonMapper.readTree(json)) {
      out.add(n.asString());
    }
    return out;
  }

  private List<String> redirectUris(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "redirect_uris must be a non-empty array.");
    }
    if (node.size() > MAX_REDIRECT_URIS) {
      throw new OAuthException(OAuthException.INVALID_REDIRECT_URI,
          "At most " + MAX_REDIRECT_URIS + " redirect_uris.");
    }
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode u : node) {
      if (!u.isString() || !redirects.isAllowed(u.asString())) {
        throw new OAuthException(OAuthException.INVALID_REDIRECT_URI, "This server only accepts the claude.ai and "
            + "chatgpt.com connector callbacks and loopback (http://localhost, http://127.0.0.1) redirect URIs: '"
            + Text.clip(u.isString() ? u.asString() : u.toString(), 200) + "' is not allowed.");
      }
      out.add(u.asString());
    }
    return List.copyOf(out);
  }

  private static Set<String> grantTypes(JsonNode node) {
    if (node == null || node.isNull()) {
      return Set.of(OAuthClient.GRANT_CODE, OAuthClient.GRANT_REFRESH);
    }
    if (!node.isArray() || node.isEmpty()) {
      throw metadata("grant_types must be a non-empty array.");
    }
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode g : node) {
      String v = g.isString() ? g.asString() : "";
      if (!v.equals(OAuthClient.GRANT_CODE) && !v.equals(OAuthClient.GRANT_REFRESH)) {
        throw metadata("Unsupported grant type '" + Text.clip(v, 60) + "' (authorization_code, refresh_token).");
      }
      out.add(v);
    }
    if (!out.contains(OAuthClient.GRANT_CODE)) {
      throw metadata("grant_types must include authorization_code.");
    }
    return out;
  }

  private static void responseTypes(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (!node.isArray()) {
      throw metadata("response_types must be an array.");
    }
    for (JsonNode r : node) {
      if (!r.isString() || !r.asString().equals("code")) {
        throw metadata("Only response_type code is supported.");
      }
    }
  }

  private static String authMethod(JsonNode node) {
    if (node == null || node.isNull()) {
      return OAuthClient.AUTH_BASIC; // RFC 7591 §2 default
    }
    String m = node.isString() ? node.asString() : "";
    if (m.equals(OAuthClient.AUTH_NONE) || m.equals(OAuthClient.AUTH_BASIC) || m.equals(OAuthClient.AUTH_POST)) {
      return m;
    }
    throw metadata("Unsupported token_endpoint_auth_method '" + Text.clip(m, 40)
        + "' (none, client_secret_basic, client_secret_post).");
  }

  private static String name(JsonNode node) {
    if (node == null || node.isNull()) {
      return DEFAULT_NAME;
    }
    String n = node.isString() ? Text.trimToNull(Text.stripControl(node.asString())) : null;
    if (n == null || n.length() > MAX_NAME) {
      throw metadata("client_name must be 1-" + MAX_NAME + " printable characters.");
    }
    return n;
  }

  private static OAuthException metadata(String description) {
    return new OAuthException(OAuthException.INVALID_CLIENT_METADATA, description);
  }
}
