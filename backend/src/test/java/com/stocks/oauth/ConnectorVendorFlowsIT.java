package com.stocks.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.nimbusds.jwt.SignedJWT;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.oauth.cimd.CimdClients;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.WebSession;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The claude.ai and ChatGPT connector flows replayed request by request as their vendors document them (2026-10-13:
 * claude.com/docs/connectors/building/authentication and troubleshooting, developers.openai.com/apps-sdk/build/auth,
 * MCP authorization 2026-07-28), from the anonymous {@code /mcp} call to a {@code tools/call place_order} as the
 * consented bot: discovery and each vendor's own choice of registration and token authentication, CIMD with the real
 * documents (served by {@link OAuthFlowIT.Documents}), DCR bodies as the vendors send them (Claude's
 * {@code jwt-bearer} grant type, ChatGPT's {@code client_secret_post} or {@code private_key_jwt}), scope omitted or
 * with {@code offline_access}, and the refresh. One admin owns both league bots and connects each vendor to its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, OAuthFlowIT.Documents.class})
class ConnectorVendorFlowsIT {

  static final String CLAUDE = OAuthFlowIT.CLAUDE;
  static final String CLAUDE_CALLBACK = OAuthFlowIT.CLAUDE_CALLBACK;
  static final String CHATGPT = "https://chatgpt.com/oauth/client.json";
  static final String CHATGPT_CALLBACK = "https://chatgpt.com/connector_platform_oauth_redirect";
  /** ChatGPT's callback-ID mode (used when an authorization server lacks issuer identification). */
  static final String CHATGPT_CB = "https://chatgpt.com/oauth/cbk_01JA2B3C4D/client.json";
  static final String CHATGPT_CB_CALLBACK = "https://chatgpt.com/connector/oauth/cbk_01JA2B3C4D";
  static final String ALL_SCOPES = "[\"market:read\",\"account:read\",\"trade\",\"league:read\"]";
  private static final AtomicInteger SEQ = new AtomicInteger();

  /** https://chatgpt.com/oauth/client.json as served on 2026-10-13 (only the id and redirect vary). */
  static String chatgptDocument(String clientId, String redirectUri) {
    return "{\"client_id\":\"" + clientId + "\",\"client_uri\":\"https://chatgpt.com/\",\"redirect_uris\":[\""
        + redirectUri + "\"],\"token_endpoint_auth_method\":\"private_key_jwt\","
        + "\"token_endpoint_auth_methods_supported\":[\"none\",\"private_key_jwt\"],"
        + "\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
        + "\"client_name\":\"ChatGPT\",\"logo_uri\":\"https://persistent.oaistatic.com/sonic/misc/openai-logo.png\","
        + "\"token_endpoint_auth_signing_alg\":\"RS256\",\"jwks_uri\":\"https://chatgpt.com/oauth/jwks.json\"}";
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired JsonMapper json;
  @Autowired CimdClients cimd;

  Fixtures fx;
  Instant t0;
  Fixtures.PlayerRef admin;
  Fixtures.PlayerRef claudeBot;
  Fixtures.PlayerRef chatgptBot;
  WebSession adminSession;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    t0 = time.now();
    int n = SEQ.incrementAndGet();
    // the league's setup: the admin created both bots, so the admin owns them (agents.md §2.9)
    admin = fx.admin("vadmin" + n);
    claudeBot = fx.ai("vclaude" + n, admin);
    chatgptBot = fx.ai("vchatgpt" + n, admin);
    jdbc.sql("UPDATE player SET ai_model = 'ChatGPT' WHERE id = ?").param(chatgptBot.id()).update();
    fx.account(claudeBot, "10000.00");
    fx.account(chatgptBot, "10000.00");
    adminSession = WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
    cimd.clear();
  }

  @AfterEach
  void resetClock() {
    time.set(t0);
  }

  // ----------------------------------------------------------------------------------------- claude.ai

  @Test
  void claudeWithItsPublishedClientIdMetadataDocument() throws Exception {
    Discovery d = discover();
    // Claude uses CIMD only when the metadata advertises CIMD and "none" (else it falls back to DCR)
    assertThat(d.as().get("client_id_metadata_document_supported").asBoolean()).isTrue();
    assertThat(strings(d.as().get("token_endpoint_auth_methods_supported"))).contains("none");
    // no scope in the 401: the protected resource's scopes_supported, plus offline_access because the AS lists it
    assertThat(strings(d.as().get("scopes_supported"))).contains("offline_access");
    String scope = String.join(" ", strings(d.prm().get("scopes_supported"))) + " offline_access";
    assertThat(scope).isEqualTo("market:read account:read trade league:read offline_access");

    OAuthTestClient claude = new OAuthTestClient(mvc, json, CLAUDE, CLAUDE_CALLBACK);
    JsonNode token = connect(d, claude, scope, claudeBot, "Claude", "CIMD", "claude.ai", Map.of());
    buyAs(token, claudeBot, "claude-cimd-1");
    JsonNode refreshed = refresh(claude, token, Map.of("resource", d.prm().get("resource").asString()));
    buyAs(refreshed, claudeBot, "claude-cimd-2");
  }

  @Test
  void claudeRegisterAutomaticallyWithDcr() throws Exception {
    Discovery d = discover();
    // "Register automatically": Claude's registration carries the jwt-bearer grant type of its metadata document
    JsonNode reg = register("""
        {"client_name":"claudeai","grant_types":["authorization_code","refresh_token",
         "urn:ietf:params:oauth:grant-type:jwt-bearer"],"response_types":["code"],"token_endpoint_auth_method":"none",
         "scope":"claudeai","redirect_uris":["https://claude.ai/api/mcp/auth_callback",
         "https://claude.com/api/mcp/auth_callback"],"client_uri":"https://claude.ai",
         "logo_uri":"https://claude.ai/favicon.ico"}""", d);
    assertThat(reg.has("client_secret")).isFalse();
    assertThat(reg.get("token_endpoint_auth_method").asString()).isEqualTo("none");
    assertThat(strings(reg.get("grant_types"))).containsExactlyInAnyOrder("authorization_code", "refresh_token");
    assertThat(strings(reg.get("redirect_uris"))).containsExactly(CLAUDE_CALLBACK,
        "https://claude.com/api/mcp/auth_callback");

    OAuthTestClient claude = new OAuthTestClient(mvc, json, reg.get("client_id").asString(), CLAUDE_CALLBACK);
    JsonNode token = connect(d, claude, null, claudeBot, "claudeai", "DCR", null, Map.of());
    buyAs(token, claudeBot, "claude-dcr-1");
    JsonNode refreshed = refresh(claude, token, Map.of("resource", OAuthTestClient.RESOURCE,
        "scope", "market:read account:read trade league:read offline_access"));
    buyAs(refreshed, claudeBot, "claude-dcr-2");
  }

  // ----------------------------------------------------------------------------------------- ChatGPT

  @Test
  void chatgptWithItsPublishedClientIdMetadataDocument() throws Exception {
    Discovery d = discover();
    // issuer identification (RFC 9207 advertised, the same issuer string in both documents): ChatGPT uses its
    // stable client.json and the stable callback
    assertThat(d.as().get("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
    assertThat(d.prm().get("authorization_servers").get(0).asString()).isEqualTo(d.as().get("issuer").asString());
    assertThat(d.as().get("client_id_metadata_document_supported").asBoolean()).isTrue();
    // its method: {none, private_key_jwt} of its document ∩ the AS's methods; private_key_jwt is not advertised
    List<String> ours = strings(d.as().get("token_endpoint_auth_methods_supported"));
    List<String> intersection = new ArrayList<>(List.of("none", "private_key_jwt"));
    intersection.retainAll(ours);
    assertThat(intersection).containsExactly("none");

    // ChatGPT asks for the advertised scopes (no offline_access, which is not a resource scope)
    String scope = String.join(" ", strings(d.prm().get("scopes_supported")));
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, CHATGPT, CHATGPT_CALLBACK);
    JsonNode token = connect(d, chatgpt, scope, chatgptBot, "ChatGPT", "CIMD", "chatgpt.com", Map.of());
    assertThat(token.get("refresh_token").asString()).as("a refresh token without offline_access").startsWith("ort_");
    buyAs(token, chatgptBot, "chatgpt-cimd-1");
    JsonNode refreshed = refresh(chatgpt, token, Map.of("resource", OAuthTestClient.RESOURCE));
    buyAs(refreshed, chatgptBot, "chatgpt-cimd-2");
  }

  @Test
  void chatgptCallbackIdModeWithScopeOmittedAndAnIgnoredAssertion() throws Exception {
    Discovery d = discover();
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, CHATGPT_CB, CHATGPT_CB_CALLBACK);
    // a client assertion (private_key_jwt) is ignored: the client is public, PKCE protects the code
    Map<String, String> assertion = Map.of("client_assertion_type",
        "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "client_assertion", "eyJhbGciOiJSUzI1NiJ9.e30.c2ln");
    JsonNode token = connect(d, chatgpt, null, chatgptBot, "ChatGPT", "CIMD", "chatgpt.com", assertion);
    buyAs(token, chatgptBot, "chatgpt-cb-1");
    buyAs(refresh(chatgpt, token, assertion), chatgptBot, "chatgpt-cb-2");
  }

  @Test
  void chatgptDcrAsAConfidentialClient() throws Exception {
    Discovery d = discover();
    JsonNode reg = register("""
        {"client_name":"ChatGPT","redirect_uris":["https://chatgpt.com/connector_platform_oauth_redirect"],
         "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
         "token_endpoint_auth_method":"client_secret_post","scope":"market:read account:read trade league:read",
         "logo_uri":"https://persistent.oaistatic.com/sonic/misc/openai-logo.png","client_uri":"https://chatgpt.com/",
         "contacts":["support@openai.com"],"tos_uri":"https://openai.com/policies/terms-of-use",
         "policy_uri":"https://openai.com/policies/privacy-policy","software_id":"chatgpt-connectors",
         "software_version":"1","application_type":"web"}""", d);
    assertThat(reg.get("token_endpoint_auth_method").asString()).isEqualTo("client_secret_post");
    assertThat(reg.get("client_secret").asString()).hasSize(43);
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, reg.get("client_id").asString(), CHATGPT_CALLBACK);
    chatgpt.secret = reg.get("client_secret").asString();
    JsonNode token = connect(d, chatgpt, "market:read account:read trade league:read", chatgptBot, "ChatGPT", "DCR",
        null, Map.of());
    buyAs(token, chatgptBot, "chatgpt-dcr-1");
    buyAs(refresh(chatgpt, token, Map.of("resource", OAuthTestClient.RESOURCE)), chatgptBot, "chatgpt-dcr-2");
  }

  @Test
  void chatgptDcrAskingForPrivateKeyJwtIsRegisteredPublic() throws Exception {
    Discovery d = discover();
    JsonNode reg = register("""
        {"client_name":"ChatGPT","redirect_uris":["https://chatgpt.com/connector/oauth/cbk_01JA2B3C4D",
         "https://chatgpt.com/aip/g-123/oauth/callback"],"grant_types":["authorization_code","refresh_token"],
         "response_types":["code"],"token_endpoint_auth_method":"private_key_jwt",
         "jwks_uri":"https://chatgpt.com/oauth/jwks.json","token_endpoint_auth_signing_alg":"RS256"}""", d);
    assertThat(reg.get("token_endpoint_auth_method").asString()).isEqualTo("none");
    assertThat(reg.has("client_secret")).isFalse();
    assertThat(strings(reg.get("redirect_uris"))).as("a URI off the allowlist is dropped, not fatal")
        .containsExactly(CHATGPT_CB_CALLBACK);
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, reg.get("client_id").asString(), CHATGPT_CB_CALLBACK);
    Map<String, String> assertion = Map.of("client_assertion_type",
        "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "client_assertion", "eyJhbGciOiJSUzI1NiJ9.e30.c2ln");
    JsonNode token = connect(d, chatgpt, null, chatgptBot, "ChatGPT", "DCR", null, assertion);
    buyAs(refresh(chatgpt, token, assertion), chatgptBot, "chatgpt-dcr-pkj");
  }

  // ----------------------------------------------------------------------------------------- one owner, two bots

  @Test
  void anAdminWhoOwnsBothBotsConnectsEachVendorToItsOwnBot() throws Exception {
    Discovery d = discover();
    OAuthTestClient claude = new OAuthTestClient(mvc, json, CLAUDE, CLAUDE_CALLBACK);
    OAuthTestClient chatgpt = new OAuthTestClient(mvc, json, CHATGPT, CHATGPT_CALLBACK);
    JsonNode claudeToken = connect(d, claude, null, claudeBot, "Claude", "CIMD", "claude.ai", Map.of());
    JsonNode chatgptToken = connect(d, chatgpt, null, chatgptBot, "ChatGPT", "CIMD", "chatgpt.com", Map.of());
    buyAs(claudeToken, claudeBot, "both-claude");
    buyAs(chatgptToken, chatgptBot, "both-chatgpt");

    JsonNode apps = json.readTree(WebSession.contentOf(adminSession.perform(get("/api/v1/me/connected-apps"))));
    Map<String, String> actAsByClient = new HashMap<>();
    for (JsonNode app : apps) {
      actAsByClient.put(app.get("client").get("name").asString(), app.get("actAs").get("handle").asString());
    }
    assertThat(actAsByClient).containsEntry("Claude", claudeBot.handle())
        .containsEntry("ChatGPT", chatgptBot.handle()).hasSize(2);

    // reconnecting Claude (a second consent) and refreshing it leaves ChatGPT's grant alone
    JsonNode again = connect(d, claude, null, claudeBot, "Claude", "CIMD", "claude.ai", Map.of());
    refresh(claude, again, Map.of());
    buyAs(chatgptToken, chatgptBot, "both-chatgpt-2");
    buyAs(refresh(chatgpt, chatgptToken, Map.of()), chatgptBot, "both-chatgpt-3");
  }

  // ----------------------------------------------------------------------------------------- the replayed steps

  record Discovery(JsonNode prm, JsonNode as) {}

  /** MCP authorization discovery as both vendors run it, from the anonymous MCP request. */
  Discovery discover() throws Exception {
    MvcResult anonymous = mcp(null, initialize());
    assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    String challenge = anonymous.getResponse().getHeader("WWW-Authenticate");
    Matcher m = Pattern.compile("resource_metadata=\"([^\"]+)\"").matcher(challenge);
    assertThat(m.find()).as(challenge).isTrue();
    URI prmUrl = URI.create(m.group(1));
    assertThat(prmUrl.toString()).isEqualTo(OAuthTestClient.ISSUER + "/.well-known/oauth-protected-resource/mcp");
    JsonNode prm = getJson(prmUrl.getPath());
    assertThat(prm.get("resource").asString()).as("equals the MCP URL the user enters").isEqualTo(OAuthTestClient.RESOURCE);
    // the probe fallbacks without a pointer: the path-suffixed document first, then the root one
    assertThat(getJson("/.well-known/oauth-protected-resource")).isEqualTo(prm);
    String issuer = prm.get("authorization_servers").get(0).asString(); // Claude uses the first entry only
    assertThat(URI.create(issuer).getRawPath()).as("an issuer without a path").isEmpty();
    // RFC 8414 first; OpenID Connect discovery is only the fallback of a client whose RFC 8414 lookup failed
    JsonNode as = getJson("/.well-known/oauth-authorization-server");
    assertThat(as.get("issuer").asString()).isEqualTo(issuer);
    assertThat(mvc.perform(get("/.well-known/openid-configuration")).andReturn().getResponse().getStatus())
        .isEqualTo(404);
    assertThat(strings(as.get("code_challenge_methods_supported"))).contains("S256");
    assertThat(strings(as.get("response_types_supported"))).contains("code");
    assertThat(strings(as.get("grant_types_supported"))).contains("authorization_code", "refresh_token");
    return new Discovery(prm, as);
  }

  /** {@code POST /oauth2/register} (DCR) with {@code body}; 201 and the registration. */
  JsonNode register(String body, Discovery d) throws Exception {
    URI endpoint = URI.create(d.as().get("registration_endpoint").asString());
    MvcResult r = mvc.perform(post(endpoint.getPath()).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
    assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isEqualTo(201);
    JsonNode reg = json.readTree(WebSession.contentOf(r));
    assertThat(reg.get("client_id").asString()).startsWith("ocl_");
    return reg;
  }

  /**
   * The browser part and the code exchange: {@code /oauth2/authorize} with PKCE S256, {@code resource} and
   * {@code state} (and {@code scope} unless null), the consent screen checked and approved as {@code actor} the way
   * the SPA does, the redirect's {@code iss} compared exactly, then {@code POST /oauth2/token}.
   */
  JsonNode connect(Discovery d, OAuthTestClient client, String scope, Fixtures.PlayerRef actor, String clientName,
                   String kind, String clientHost, Map<String, String> tokenExtras) throws Exception {
    OAuthTestClient.Attempt a = OAuthTestClient.attempt();
    Map<String, String> extra = new HashMap<>();
    extra.put("resource", d.prm().get("resource").asString());
    extra.put("prompt", "consent"); // ignored: the act-as consent is always shown
    String requestId = OAuthTestClient.requestId(client.authorize(a, scope, extra));

    // what the consent screen gets: the app (its family picks the preselected bot), the redirect host, both bots
    JsonNode consent = json.readTree(WebSession.contentOf(adminSession.perform(get("/api/v1/me/oauth/requests/"
        + requestId))));
    assertThat(consent.get("client").get("name").asString()).isEqualTo(clientName);
    assertThat(consent.get("client").get("kind").asString()).isEqualTo(kind);
    if (clientHost == null) {
      assertThat(consent.get("client").get("host").isNull()).isTrue();
    } else {
      assertThat(consent.get("client").get("host").asString()).isEqualTo(clientHost);
    }
    assertThat(consent.get("redirectHost").asString()).isEqualTo(RedirectUris.host(client.redirectUri));
    assertThat(consent.get("requestedScopes").toString())
        .isEqualTo("[\"account:read\",\"league:read\",\"market:read\",\"trade\"]");
    Map<String, JsonNode> actors = new LinkedHashMap<>();
    for (JsonNode actorNode : consent.get("actors")) {
      actors.put(actorNode.get("player").get("handle").asString(), actorNode);
    }
    // the bots by handle (so the ChatGPT bot comes first: preselection must not take the first one), then myself
    assertThat(actors.keySet()).containsExactly(chatgptBot.handle(), claudeBot.handle(), admin.handle());
    for (Fixtures.PlayerRef bot : List.of(claudeBot, chatgptBot)) {
      assertThat(strings(actors.get(bot.handle()).get("allowedScopes"))).contains("trade");
      assertThat(actors.get(bot.handle()).get("self").asBoolean()).isFalse();
    }

    String redirect = client.approve(adminSession, requestId, actor.handle(), ALL_SCOPES);
    assertThat(redirect).startsWith(client.redirectUri + "?code=");
    Map<String, String> q = OAuthTestClient.query(redirect);
    assertThat(q.get("state")).isEqualTo(a.state());
    assertThat(q.get("iss")).as("RFC 9207, compared as a plain string").isEqualTo(d.as().get("issuer").asString());

    Map<String, String> form = new LinkedHashMap<>(tokenExtras);
    form.put("grant_type", "authorization_code");
    form.put("code", q.get("code"));
    form.put("code_verifier", a.verifier());
    form.put("redirect_uri", client.redirectUri);
    form.put("resource", d.prm().get("resource").asString());
    MvcResult token = client.token(form);
    assertThat(token.getResponse().getStatus()).as(WebSession.contentOf(token)).isEqualTo(200);
    assertThat(token.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    JsonNode t = json.readTree(WebSession.contentOf(token));
    assertThat(t.get("token_type").asString()).isEqualTo("Bearer");
    assertThat(t.get("refresh_token").asString()).startsWith("ort_");
    assertThat(t.get("scope").asString()).isEqualTo("account:read league:read market:read trade");
    JsonNode claims = json.readTree(SignedJWT.parse(t.get("access_token").asString()).getJWTClaimsSet().toString());
    assertThat(claims.get("sub").asString()).isEqualTo(actor.publicId());
    assertThat(claims.get("act").get("sub").asString()).isEqualTo(admin.publicId());
    assertThat(claims.get("aud").toString()).contains(OAuthTestClient.RESOURCE);
    assertThat(claims.get("client_id").asString()).isEqualTo(client.clientId);
    return t;
  }

  /** {@code grant_type=refresh_token} plus {@code extras}; a new pair, the old refresh token rotated. */
  JsonNode refresh(OAuthTestClient client, JsonNode token, Map<String, String> extras) throws Exception {
    Map<String, String> form = new LinkedHashMap<>(extras);
    form.put("grant_type", "refresh_token");
    form.put("refresh_token", token.get("refresh_token").asString());
    MvcResult r = client.token(form);
    assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isEqualTo(200);
    JsonNode t = json.readTree(WebSession.contentOf(r));
    assertThat(t.get("refresh_token").asString()).startsWith("ort_")
        .isNotEqualTo(token.get("refresh_token").asString());
    assertThat(t.get("scope").asString()).isEqualTo("account:read league:read market:read trade");
    return t;
  }

  /** The MCP session a connector runs: initialize, initialized, tools/list, whoami, then a $25 market buy. */
  void buyAs(JsonNode token, Fixtures.PlayerRef bot, String clientOrderId) throws Exception {
    String access = token.get("access_token").asString();
    MvcResult init = mcp(access, initialize());
    assertThat(init.getResponse().getStatus()).as(WebSession.contentOf(init)).isEqualTo(200);
    assertThat(json.readTree(WebSession.contentOf(init)).get("result").get("serverInfo").get("name").asString())
        .isEqualTo("stocks");
    assertThat(mcp(access, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").getResponse()
        .getStatus()).isEqualTo(202);
    JsonNode tools = json.readTree(WebSession.contentOf(mcp(access,
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))).get("result").get("tools");
    List<String> names = new ArrayList<>();
    tools.forEach(tool -> names.add(tool.get("name").asString()));
    assertThat(names).contains("whoami", "get_quotes", "place_order");
    JsonNode me = call(access, "whoami", "{}").get("structuredContent").get("me");
    assertThat(me.get("handle").asString()).isEqualTo(bot.handle());
    assertThat(me.get("authMethod").asString()).isEqualTo("OAUTH");
    JsonNode placed = call(access, "place_order", "{\"client_order_id\":\"" + clientOrderId + "\",\"symbol\":\"AAPL\","
        + "\"side\":\"BUY\",\"type\":\"MARKET\",\"notional\":\"25.00\",\"rationale\":\"Connector smoke test.\"}");
    JsonNode order = placed.get("structuredContent").get("order");
    assertThat(order.get("orderId").asString()).startsWith("ord_");
    assertThat(order.get("side").asString()).isEqualTo("BUY");
    assertThat(order.get("symbol").asString()).isEqualTo("AAPL");
  }

  /** {@code tools/call}; the JSON-RPC result, which must not be an error. */
  JsonNode call(String access, String tool, String arguments) throws Exception {
    MvcResult r = mcp(access, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":" + arguments + "}}");
    assertThat(r.getResponse().getStatus()).as(WebSession.contentOf(r)).isEqualTo(200);
    JsonNode result = json.readTree(WebSession.contentOf(r)).get("result");
    assertThat(result.path("isError").asBoolean(false)).as(result.toString()).isFalse();
    return result;
  }

  /** A Streamable HTTP POST as a connector sends it (no Origin: server-to-server). */
  MvcResult mcp(String accessToken, String body) throws Exception {
    var request = post("/mcp").contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .header("MCP-Protocol-Version", "2025-11-25").content(body);
    if (accessToken != null) {
      request.header("Authorization", "Bearer " + accessToken);
    }
    return mvc.perform(request).andReturn();
  }

  static String initialize() {
    return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"connector-replay\",\"version\":\"1\"}}}";
  }

  JsonNode getJson(String path) throws Exception {
    MvcResult r = mvc.perform(get(path)).andReturn();
    assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
    return json.readTree(WebSession.contentOf(r));
  }

  static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }
}
