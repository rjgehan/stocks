package com.stocks.oauth.web;

import com.stocks.common.domain.Channel;
import com.stocks.oauth.AuthorizationEndpoint;
import com.stocks.oauth.DcrClients;
import com.stocks.oauth.OAuthClient;
import com.stocks.oauth.OAuthClients;
import com.stocks.oauth.OAuthException;
import com.stocks.oauth.OAuthProperties;
import com.stocks.oauth.OAuthRateLimiter;
import com.stocks.oauth.OAuthUrls;
import com.stocks.oauth.TokenEndpoint;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorization server's protocol endpoints (§17.9.1): {@code GET /oauth2/authorize} (a browser redirect),
 * {@code POST /oauth2/token} and {@code POST /oauth2/revoke} ({@code application/x-www-form-urlencoded}), and
 * {@code POST /oauth2/register} (RFC 7591, JSON ≤ 16 KiB). Errors are RFC 6749 JSON ({@link OAuthErrors}); the
 * token, revoke and register answers are {@code no-store} and readable cross-origin without credentials. Each
 * endpoint has its own per-IP limit ({@link OAuthRateLimiter}).
 */
@RestController
@Hidden
public class OAuthServerController {

  static final int MAX_REGISTRATION_BYTES = 16 * 1024;

  private final AuthorizationEndpoint authorization;
  private final TokenEndpoint tokens;
  private final OAuthClients clients;
  private final DcrClients dcr;
  private final OAuthRateLimiter limiter;
  private final OAuthProperties properties;
  private final JsonMapper jsonMapper;

  public OAuthServerController(AuthorizationEndpoint authorization, TokenEndpoint tokens, OAuthClients clients,
                               DcrClients dcr, OAuthRateLimiter limiter, OAuthProperties properties,
                               JsonMapper jsonMapper) {
    this.authorization = authorization;
    this.tokens = tokens;
    this.clients = clients;
    this.dcr = dcr;
    this.limiter = limiter;
    this.properties = properties;
    this.jsonMapper = jsonMapper;
  }

  /** §17.9.3 step 1: a 302 to the consent page, the client (errors) or the error page. */
  @GetMapping(OAuthUrls.AUTHORIZE_PATH)
  public ResponseEntity<Void> authorize(HttpServletRequest request) {
    enabled();
    try {
      limiter.charge(OAuthRateLimiter.Bucket.AUTHORIZE, OAuthRequests.ip(request));
    } catch (OAuthException e) {
      return found(AuthorizationEndpoint.errorPage(e.error(), e.description()).location());
    }
    AuthorizationEndpoint.Outcome outcome = authorization.authorize(request.getParameterMap());
    return found(outcome.location());
  }

  @PostMapping(OAuthUrls.TOKEN_PATH)
  @CrossOrigin(origins = "*", allowCredentials = "false", methods = RequestMethod.POST)
  public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form,
                                                   HttpServletRequest request) {
    enabled();
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, OAuthRequests.ip(request));
    Map<String, String> params = OAuthRequests.single(form);
    String grantType = params.get("grant_type");
    if (grantType == null) {
      throw new OAuthException(OAuthException.INVALID_REQUEST, "grant_type is required.");
    }
    OAuthClient client = authenticate(request, params);
    var meta = OAuthRequests.meta(request, Channel.MCP_OAUTH, client.name());
    return switch (grantType) {
      case OAuthClient.GRANT_CODE -> OAuthRequests.noStore(tokens.exchangeCode(client, params, meta));
      case OAuthClient.GRANT_REFRESH -> {
        if (!client.allowsRefresh()) {
          throw new OAuthException(OAuthException.UNAUTHORIZED_CLIENT,
              "This client did not register the refresh_token grant.");
        }
        yield OAuthRequests.noStore(tokens.refresh(client, params, meta));
      }
      default -> throw new OAuthException(OAuthException.UNSUPPORTED_GRANT_TYPE,
          "Supported grant types: authorization_code, refresh_token.");
    };
  }

  @PostMapping(OAuthUrls.REVOKE_PATH)
  @CrossOrigin(origins = "*", allowCredentials = "false", methods = RequestMethod.POST)
  public ResponseEntity<Void> revoke(@RequestParam MultiValueMap<String, String> form, HttpServletRequest request) {
    enabled();
    limiter.charge(OAuthRateLimiter.Bucket.TOKEN, OAuthRequests.ip(request));
    Map<String, String> params = OAuthRequests.single(form);
    OAuthClient client = authenticate(request, params);
    tokens.revoke(client, params.get("token"), OAuthRequests.meta(request, Channel.MCP_OAUTH, client.name()));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
  }

  @PostMapping(OAuthUrls.REGISTER_PATH)
  @CrossOrigin(origins = "*", allowCredentials = "false", methods = RequestMethod.POST)
  public ResponseEntity<Map<String, Object>> register(HttpServletRequest request) throws IOException {
    enabled();
    if (!properties.dcr().enabled()) {
      throw OAuthException.disabled();
    }
    limiter.charge(OAuthRateLimiter.Bucket.REGISTER, OAuthRequests.ip(request));
    String type = request.getContentType();
    if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type))) {
      throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, "Send the registration as application/json.");
    }
    byte[] body;
    try (InputStream in = request.getInputStream()) {
      body = in.readNBytes(MAX_REGISTRATION_BYTES + 1);
    }
    if (body.length > MAX_REGISTRATION_BYTES) {
      throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA,
          "The registration request is larger than 16 KiB.");
    }
    JsonNode json;
    try {
      json = jsonMapper.readTree(body);
    } catch (JacksonException e) {
      throw new OAuthException(OAuthException.INVALID_CLIENT_METADATA, "The registration request is not valid JSON.");
    }
    DcrClients.Registered registered = dcr.register(json, OAuthRequests.ip(request),
        OAuthRequests.header(request, HttpHeaders.USER_AGENT));
    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
        .body(dcr.response(registered));
  }

  private OAuthClient authenticate(HttpServletRequest request, Map<String, String> params) {
    OAuthClients.Credentials credentials = OAuthClients.credentials(
        OAuthRequests.header(request, HttpHeaders.AUTHORIZATION), params.get("client_id"),
        params.get("client_secret"));
    if (credentials.clientId() == null) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "client_id is required.");
    }
    return clients.authenticate(credentials);
  }

  private void enabled() {
    if (!properties.enabled()) {
      throw OAuthException.disabled();
    }
  }

  private static ResponseEntity<Void> found(String location) {
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location))
        .cacheControl(CacheControl.noStore()).build();
  }
}
