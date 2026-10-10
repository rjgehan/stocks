package com.stocks.oauth.web;

import com.stocks.contract.security.Scopes;
import com.stocks.oauth.OAuthException;
import com.stocks.oauth.OAuthProperties;
import com.stocks.oauth.OAuthUrls;
import com.stocks.oauth.SigningKeys;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public discovery documents of §17.9.1, cacheable for 5 minutes and readable cross-origin (browser-based MCP
 * tools): RFC 9728 protected-resource metadata for {@code ${public-url}/mcp} (at the path-inserted URL and at the
 * root), RFC 8414 authorization-server metadata, and the JWK set. All URLs come from {@code stocks.public-url}, never
 * from the request.
 */
@RestController
@Hidden
@CrossOrigin(origins = "*", allowCredentials = "false", methods = {org.springframework.web.bind.annotation.RequestMethod.GET})
public class OAuthMetadataController {

  static final CacheControl PUBLIC_5_MIN = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

  private final OAuthUrls urls;
  private final OAuthProperties properties;
  private final SigningKeys keys;

  public OAuthMetadataController(OAuthUrls urls, OAuthProperties properties, SigningKeys keys) {
    this.urls = urls;
    this.properties = properties;
    this.keys = keys;
  }

  @GetMapping({OAuthUrls.PROTECTED_RESOURCE_PATH + OAuthUrls.MCP_PATH, OAuthUrls.PROTECTED_RESOURCE_PATH})
  public ResponseEntity<Map<String, Object>> protectedResource() {
    enabled();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("resource", urls.resource());
    out.put("authorization_servers", List.of(urls.issuer()));
    out.put("scopes_supported", apiScopes());
    out.put("bearer_methods_supported", List.of("header"));
    out.put("resource_name", "stocks");
    return ResponseEntity.ok().cacheControl(PUBLIC_5_MIN).body(out);
  }

  @GetMapping(OAuthUrls.AUTHORIZATION_SERVER_PATH)
  public ResponseEntity<Map<String, Object>> authorizationServer() {
    enabled();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("issuer", urls.issuer());
    out.put("authorization_endpoint", urls.endpoint(OAuthUrls.AUTHORIZE_PATH));
    out.put("token_endpoint", urls.endpoint(OAuthUrls.TOKEN_PATH));
    if (properties.dcr().enabled()) {
      out.put("registration_endpoint", urls.endpoint(OAuthUrls.REGISTER_PATH));
    }
    out.put("revocation_endpoint", urls.endpoint(OAuthUrls.REVOKE_PATH));
    out.put("jwks_uri", urls.endpoint(OAuthUrls.JWKS_PATH));
    List<String> scopes = new ArrayList<>(apiScopes());
    scopes.add("offline_access");
    out.put("scopes_supported", scopes);
    out.put("response_types_supported", List.of("code"));
    out.put("response_modes_supported", List.of("query"));
    out.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
    List<String> methods = List.of("none", "client_secret_basic", "client_secret_post");
    out.put("token_endpoint_auth_methods_supported", methods);
    out.put("revocation_endpoint_auth_methods_supported", methods);
    out.put("code_challenge_methods_supported", List.of("S256"));
    out.put("client_id_metadata_document_supported", properties.cimd().enabled());
    out.put("authorization_response_iss_parameter_supported", true);
    return ResponseEntity.ok().cacheControl(PUBLIC_5_MIN).body(out);
  }

  @GetMapping(OAuthUrls.JWKS_PATH)
  public ResponseEntity<Map<String, Object>> jwks() {
    enabled();
    return ResponseEntity.ok().cacheControl(PUBLIC_5_MIN).body(keys.publicJwkSet());
  }

  /** The four API scopes in §7.1 order ({@code offline_access} is not a resource scope, MCP). */
  static List<String> apiScopes() {
    return List.of(Scopes.MARKET_READ, Scopes.ACCOUNT_READ, Scopes.TRADE, Scopes.LEAGUE_READ);
  }

  private void enabled() {
    if (!properties.enabled()) {
      throw OAuthException.disabled();
    }
  }
}
