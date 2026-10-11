package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.testkit.WebSession;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A scripted OAuth 2.1 public client for the ITs (§17.9): PKCE S256, the authorization request, the consent API as
 * the signed-in human, and the token endpoint, all through MockMvc and the real filter chains.
 */
final class OAuthTestClient {

  static final String RESOURCE = "http://localhost:5173/mcp";
  static final String ISSUER = "http://localhost:5173";

  final MockMvc mvc;
  final JsonMapper json;
  final String clientId;
  final String redirectUri;
  String secret;
  boolean basic;

  OAuthTestClient(MockMvc mvc, JsonMapper json, String clientId, String redirectUri) {
    this.mvc = mvc;
    this.json = json;
    this.clientId = clientId;
    this.redirectUri = redirectUri;
  }

  /** One authorization attempt: the PKCE pair and the state. */
  record Attempt(String verifier, String challenge, String state) {}

  static Attempt attempt() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Attempt(verifier, Secrets.s256(verifier), "st-" + UUID.randomUUID());
  }

  /** {@code GET /oauth2/authorize} with the usual parameters plus {@code extra} (a null value removes one). */
  MvcResult authorize(Attempt a, String scope, Map<String, String> extra) throws Exception {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("response_type", "code");
    p.put("client_id", clientId);
    p.put("redirect_uri", redirectUri);
    p.put("code_challenge", a.challenge());
    p.put("code_challenge_method", "S256");
    p.put("state", a.state());
    p.put("resource", RESOURCE);
    if (scope != null) {
      p.put("scope", scope);
    }
    if (extra != null) {
      extra.forEach((k, v) -> {
        if (v == null) {
          p.remove(k);
        } else {
          p.put(k, v);
        }
      });
    }
    MockHttpServletRequestBuilder r = get("/oauth2/authorize");
    p.forEach((k, v) -> r.queryParam(k, v));
    return mvc.perform(r).andReturn();
  }

  /** The {@code oar_…} id of a consent redirect. */
  static String requestId(MvcResult authorize) {
    assertThat(authorize.getResponse().getStatus()).as(WebSession.contentOf(authorize)).isEqualTo(302);
    String location = authorize.getResponse().getHeader("Location");
    assertThat(location).startsWith("/oauth/consent?request=oar_");
    return location.substring("/oauth/consent?request=".length());
  }

  /** Approves as {@code session}; returns the redirect URL the SPA would navigate to. */
  String approve(WebSession session, String requestId, String actAs, String scopesJsonOrNull) throws Exception {
    MvcResult r = decide(session, requestId, "{\"approve\":true,\"actAs\":\"" + actAs + "\""
        + (scopesJsonOrNull == null ? "" : ",\"scopes\":" + scopesJsonOrNull) + "}");
    assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isEqualTo(200);
    return json.readTree(WebSession.contentOf(r)).get("redirectUrl").asString();
  }

  MvcResult decide(WebSession session, String requestId, String body) {
    return session.perform(post("/api/v1/me/oauth/requests/" + requestId).contentType(MediaType.APPLICATION_JSON)
        .content(body));
  }

  static Map<String, String> query(String url) {
    Map<String, String> out = new LinkedHashMap<>();
    String q = URI.create(url).getRawQuery();
    if (q == null) {
      return out;
    }
    for (String part : q.split("&")) {
      int eq = part.indexOf('=');
      out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  /** Full flow: authorize, approve as {@code actAs}, exchange the code. Returns the token response. */
  JsonNode connect(WebSession session, String actAs, String scope) throws Exception {
    Attempt a = attempt();
    String requestId = requestId(authorize(a, scope, null));
    String redirect = approve(session, requestId, actAs, null);
    Map<String, String> q = query(redirect);
    assertThat(q).containsEntry("state", a.state()).containsEntry("iss", ISSUER);
    MvcResult token = token(Map.of("grant_type", "authorization_code", "code", q.get("code"), "code_verifier",
        a.verifier(), "redirect_uri", redirectUri, "resource", RESOURCE));
    assertThat(token.getResponse().getStatus()).as(WebSession.contentOf(token)).isEqualTo(200);
    return json.readTree(WebSession.contentOf(token));
  }

  /** {@code POST /oauth2/token} with this client's authentication. */
  MvcResult token(Map<String, String> form) throws Exception {
    return form("/oauth2/token", form);
  }

  MvcResult revoke(String token) throws Exception {
    return form("/oauth2/revoke", Map.of("token", token));
  }

  MvcResult refresh(String refreshToken) throws Exception {
    return token(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
  }

  private MvcResult form(String path, Map<String, String> form) throws Exception {
    MockHttpServletRequestBuilder r = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
    form.forEach((k, v) -> r.param(k, v));
    if (secret != null && basic) {
      r.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + secret)
          .getBytes(StandardCharsets.UTF_8)));
    } else {
      r.param("client_id", clientId);
      if (secret != null) {
        r.param("client_secret", secret);
      }
    }
    return mvc.perform(r).andReturn();
  }

  /** {@code POST /mcp} {@code tools/call} with {@code Authorization: Bearer <accessToken>}. */
  static MvcResult mcpCall(MockMvc mvc, String accessToken, String tool, String argumentsJson) throws Exception {
    return mvc.perform(post("/mcp").header("Authorization", "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
            + "\",\"arguments\":" + argumentsJson + "}}")).andReturn();
  }
}
