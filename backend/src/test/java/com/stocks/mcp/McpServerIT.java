package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.common.web.RequestIds;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.RateLimitStatus;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.ViolationDto;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestClockConfig;
import com.stocks.testkit.TestTimes;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Day-1 spike (§20.1, §26 "MCP", §28 #13 and #20): the real Spring AI 2.0.1 MCP server on a random port, driven by
 * the MCP Java SDK client and by raw HTTP. A bearer API key authenticates {@code POST /mcp} (security chain 2), the
 * overridden transport's {@code contextExtractor} hands the key's player to {@code whoami}, and every MCP payload is
 * written by Boot's {@code JsonMapper} (decimals as strings, nulls present) against post-processed output schemas.
 *
 * <p>{@code AccountQueries} and {@code RateLimitService} are test doubles here (the Day-0 stubs answer 501), so the
 * {@code account} and {@code rateLimits} members carry real decimals and integers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=-1")
@ActiveProfiles("test")
@Import({TestClockConfig.class, McpServerIT.Doubles.class})
class McpServerIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @TestConfiguration(proxyBeanMethods = false)
  static class Doubles {

    /** {@code AccountQueries} for the read side of {@code whoami}: the ACTIVE account row, fixed decimal values. */
    @Bean
    @Primary   // WS3's real AccountQueries also exists; this double keeps whoami's numbers fixed
    AccountQueries testAccountQueries(JdbcClient jdbc) {
      return new AccountQueries() {
        @Override
        public long activeAccountId(long playerId) {
          return jdbc.sql("SELECT id FROM account WHERE player_id = ? AND status = 'ACTIVE'").param(playerId)
              .query(Long.class).optional()
              .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No active account."));
        }

        @Override
        public AccountDto account(long accountId) {
          String publicId = jdbc.sql("SELECT public_id FROM account WHERE id = ?").param(accountId)
              .query(String.class).single();
          BigDecimal cash = new BigDecimal("500.00");
          BigDecimal zero = new BigDecimal("0.00");
          return new AccountDto(publicId, 1, AccountType.CASH, AccountStatus.ACTIVE, AccountRestriction.NONE, null,
              null, cash, zero, zero, new AccountDto.AccountCashDto(cash, cash, zero, cash, cash), zero, cash, cash,
              cash, zero, new BigDecimal("0.0000000000"), new BigDecimal("1.000000000000000000"), zero,
              new BigDecimal("0.0000000000"), new AccountDto.AccountGfvDto(0, null, 3, 4), 0, TestTimes.START,
              DataQuality.SIMULATED, TestTimes.START);
        }

        @Override
        public Balances balances(long accountId, LocalDate asOfEt) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<PositionDto> positions(long accountId) {
          throw new UnsupportedOperationException();
        }

        @Override
        public Page<ActivityDto> activities(long accountId, ActivityFilter filter, Cursor cursor, int limit) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<ViolationDto> violations(long accountId) {
          throw new UnsupportedOperationException();
        }

        @Override
        public List<RunDto> runs(long playerId) {
          throw new UnsupportedOperationException();
        }
      };
    }

    /**
     * Test-only tools, registered as an extra tool list outside the §20.3 catalog: one reports what the transport's
     * {@code contextExtractor} put into the {@link McpTransportContext} (read directly, without
     * {@code CurrentPlayer}'s SecurityContextHolder fallback); the other needs {@code account:read}, like the
     * account tools of §20.3, so the per-call scope check runs through the real server.
     */
    @Bean
    List<SyncToolSpecification> contextProbeTool(@Qualifier("mcpServerJsonMapper") JsonMapper mapper)
        throws NoSuchMethodException {
      ContextProbe probe = new ContextProbe();
      return List.of(
          McpToolCatalog.specification(probe, ContextProbe.class.getMethod("probe", McpTransportContext.class),
              mapper, McpToolCatalog.NO_SCOPE),
          McpToolCatalog.specification(probe, ContextProbe.class.getMethod("scoped", McpTransportContext.class),
              mapper, Scopes.ACCOUNT_READ));
    }

    @Bean
    RateLimitService testRateLimits() {
      return new RateLimitService() {
        @Override
        public RateLimitStatus remaining(long playerId) {
          return new RateLimitStatus(117, 20, 299, TestTimes.START);
        }

        @Override
        public void consumeWrite(long playerId) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void refundWrite(long playerId) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  public record ContextProbeDto(String principalHandle, String authMethod, String channel, String mcpClient,
                                 String mcpTool, String requestId, String ip) {}

  public static class ContextProbe {
    @McpTool(name = PROBE, generateOutputSchema = true, annotations = @McpTool.McpAnnotations(readOnlyHint = true,
        destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ContextProbeDto probe(McpTransportContext ctx) {
      PlayerPrincipal p = (PlayerPrincipal) ctx.get(CurrentPlayer.PRINCIPAL_KEY);
      RequestMeta m = (RequestMeta) ctx.get(CurrentPlayer.REQUEST_META_KEY);
      return new ContextProbeDto(p == null ? null : p.handle(), p == null ? null : p.authMethod().name(),
          m.channel().name(), m.mcpClient(), m.mcpTool(), m.requestId(), m.ip());
    }

    @McpTool(name = SCOPED_PROBE, generateOutputSchema = true, annotations = @McpTool.McpAnnotations(
        readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ContextProbeDto scoped(McpTransportContext ctx) {
      return probe(ctx);
    }
  }

  static final String PROBE = "__context_probe";
  static final String SCOPED_PROBE = "__account_read_probe";

  @LocalServerPort int port;
  @Autowired JdbcClient jdbc;
  @Autowired MutableTimeSource time;
  @Autowired StocksProperties properties;
  @Autowired Environment environment;
  @Autowired ConfigurableApplicationContext context;
  @Autowired JsonMapper jsonMapper;

  Fixtures fx;
  Fixtures.PlayerRef owner;
  Fixtures.PlayerRef bot;
  Fixtures.AccountRef botAccount;
  String botKey;

  @BeforeEach
  void setUp() {
    fx = new Fixtures(jdbc, time);
    owner = fx.human("owner" + SEQ.incrementAndGet());
    bot = fx.ai("claude" + SEQ.incrementAndGet(), owner);
    botAccount = fx.account(bot, "500.00");
    botKey = fx.apiKey(bot, properties.security().apiKeyPepper());
  }

  // ------------------------------------------------------------------------------------------- MCP Java SDK client

  @Test
  void sdkClientInitializesListsAndCallsWhoamiWithABearerKey() {
    try (McpSyncClient client = client(botKey)) {
      InitializeResult init = client.initialize();
      assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
      assertThat(init.serverInfo().name()).isEqualTo("stocks");
      assertThat(init.serverInfo().version()).isEqualTo(environment.getProperty("stocks.version")).isNotBlank();
      assertThat(init.instructions()).contains("get_rules").contains("client_order_id").contains("untrusted")
          .contains("retry_after_s").contains("preview_order");
      assertThat(init.capabilities().tools()).isNotNull();
      assertThat(init.capabilities().resources()).isNull();
      assertThat(init.capabilities().prompts()).isNull();

      List<Tool> tools = client.listTools().tools();
      assertThat(tools).extracting(Tool::name).containsExactly("whoami", PROBE, SCOPED_PROBE); // probes: this test's
      Tool whoami = tools.get(0);
      assertThat(whoami.title()).isEqualTo("Who am I");
      assertThat(whoami.annotations().readOnlyHint()).isTrue();
      assertThat(whoami.annotations().destructiveHint()).isFalse();
      assertThat(whoami.annotations().idempotentHint()).isTrue();
      assertThat(whoami.annotations().openWorldHint()).isFalse();
      assertThat(whoami.inputSchema()).containsEntry("type", "object");
      assertThat((Map<?, ?>) whoami.inputSchema().get("properties")).isEmpty();
      assertThat(jsonMapper.writeValueAsString(whoami.outputSchema())).doesNotContain("\"number\"");

      CallToolResult result = client.callTool(new CallToolRequest("whoami", Map.of()));
      assertThat(result.isError()).as(String.valueOf(result.content())).isFalse();
      JsonNode structured = jsonMapper.valueToTree(result.structuredContent());
      JsonNode me = structured.get("me");
      assertThat(me.get("playerId").asString()).isEqualTo(bot.publicId());
      assertThat(me.get("handle").asString()).isEqualTo(bot.handle());
      assertThat(me.get("kind").asString()).isEqualTo("AI");
      assertThat(me.get("authMethod").asString()).isEqualTo("API_KEY");
      assertThat(me.get("accountId").asString()).isEqualTo(botAccount.publicId());
      assertThat(me.get("scopes").valueStream().map(JsonNode::asString))
          .containsExactly("account:read", "league:read", "market:read", "trade");
      assertThat(structured.get("account").get("accountId").asString()).isEqualTo(botAccount.publicId());
      assertThat(structured.get("account").get("equity").isString()).isTrue();
      assertThat(structured.get("rateLimits").get("readsRemaining").asInt()).isEqualTo(117);

      // §20.2: structuredContent plus the same JSON as a text block; it validates against the advertised schema
      assertThat(result.content()).hasSize(1);
      String text = ((TextContent) result.content().get(0)).text();
      assertThat(jsonMapper.readTree(text)).isEqualTo(structured);
      JsonSchemaValidator.ValidationResponse validation =
          McpJsonDefaults.getSchemaValidator().validate(whoami.outputSchema(), result.structuredContent());
      assertThat(validation.valid()).as(validation.errorMessage()).isTrue();
    }
  }

  @Test
  void aKeyOfAnotherPlayerSeesThatPlayer() {
    Fixtures.PlayerRef human = fx.human("alice" + SEQ.incrementAndGet());
    String key = fx.apiKey(human, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    try (McpSyncClient client = client(key)) {
      client.initialize();
      CallToolResult result = client.callTool(new CallToolRequest("whoami", Map.of()));
      JsonNode me = jsonMapper.valueToTree(result.structuredContent()).get("me");
      assertThat(me.get("handle").asString()).isEqualTo(human.handle());
      assertThat(me.get("kind").asString()).isEqualTo("HUMAN");
      assertThat(me.get("scopes").valueStream().map(JsonNode::asString)).containsExactly("market:read");
    }
  }

  @Test
  void toolsEnforceTheirScopeAndWhoamiHidesTheAccountWithoutAccountRead() throws Exception {
    // §20.3: chain 2 lets any authenticated key reach /mcp, so each tool checks its own scope (as REST does, §17.1)
    String marketOnly = fx.apiKey(bot, Set.of(Scopes.MARKET_READ), properties.security().apiKeyPepper());
    try (McpSyncClient client = client(marketOnly)) {
      client.initialize();
      CallToolResult denied = client.callTool(new CallToolRequest(SCOPED_PROBE, Map.of()));
      assertThat(denied.isError()).isTrue();
      assertThat(denied.structuredContent()).isNull();
      JsonNode error = jsonMapper.readTree(((TextContent) denied.content().get(0)).text());
      assertThat(error.get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
      assertThat(error.get("message").asString()).contains("account:read");

      CallToolResult whoami = client.callTool(new CallToolRequest("whoami", Map.of()));
      assertThat(whoami.isError()).as(String.valueOf(whoami.content())).isFalse();
      JsonNode structured = jsonMapper.valueToTree(whoami.structuredContent());
      assertThat(structured.get("me").get("accountId").asString()).isEqualTo(botAccount.publicId());
      assertThat(structured.get("account").isNull()).as("GET /account needs account:read").isTrue();
    }
    try (McpSyncClient client = client(botKey)) {   // all four scopes
      client.initialize();
      CallToolResult allowed = client.callTool(new CallToolRequest(SCOPED_PROBE, Map.of()));
      assertThat(allowed.isError()).as(String.valueOf(allowed.content())).isFalse();
      assertThat(jsonMapper.valueToTree(allowed.structuredContent()).get("mcpTool").asString())
          .isEqualTo(SCOPED_PROBE);
    }
  }

  @Test
  void theContextExtractorCarriesThePrincipalAndRequestMeta() {
    try (McpSyncClient client = client(botKey)) {
      client.initialize();
      CallToolResult result = client.callTool(new CallToolRequest(PROBE, Map.of()));
      assertThat(result.isError()).as(String.valueOf(result.content())).isFalse();
      JsonNode ctx = jsonMapper.valueToTree(result.structuredContent());
      assertThat(ctx.get("principalHandle").asString()).isEqualTo(bot.handle());
      assertThat(ctx.get("authMethod").asString()).isEqualTo("API_KEY");
      assertThat(ctx.get("channel").asString()).isEqualTo("MCP_KEY");
      assertThat(ctx.get("mcpClient").asString()).isEqualTo("mcp-server-it");      // X-Stocks-Client
      assertThat(ctx.get("mcpTool").asString()).isEqualTo(PROBE);
      assertThat(ctx.get("requestId").asString()).startsWith("req_");
      assertThat(ctx.get("ip").asString()).isEqualTo("127.0.0.1");
    }
  }

  // ------------------------------------------------------------------------------------------- raw HTTP

  @Test
  void rawCallsUseTheUserAgentAndTheClientRequestId() throws Exception {
    HttpResponse<String> r = post(botKey, call(PROBE),
        Map.of("User-Agent", "raw-agent/1.0", RequestIds.HEADER, "it-req-42"));
    JsonNode ctx = jsonMapper.readTree(r.body()).get("result").get("structuredContent");
    assertThat(ctx.get("mcpClient").asString()).isEqualTo("raw-agent/1.0");
    assertThat(ctx.get("requestId").asString()).isEqualTo("it-req-42");
    assertThat(r.headers().firstValue(RequestIds.HEADER)).hasValue("it-req-42");
  }

  @Test
  void rawWhoamiIsBootJsonDecimalsAsStringsAndNullsPresent() throws Exception {
    HttpResponse<String> r = post(botKey, call("whoami"), Map.of());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith(
        "application/json"));
    assertThat(r.headers().firstValue("Cache-Control")).hasValue("no-store");
    String body = r.body();
    assertThat(body).contains("\"equity\":\"500.00\"", "\"unitValue\":\"1.000000000000000000\"",
        "\"totalReturn\":\"0.0000000000\"", "\"restrictionUntil\":null", "\"nextExpiry\":null",
        "\"asOf\":\"2026-10-09T14:00:00.000Z\"");
    assertThat(body).doesNotContain("\"equity\":500");

    // a human without an account: null members are present, not dropped
    Fixtures.PlayerRef human = fx.human("bob" + SEQ.incrementAndGet());
    String humanBody = post(fx.apiKey(human, properties.security().apiKeyPepper()), call("whoami"), Map.of()).body();
    assertThat(humanBody).contains("\"accountId\":null", "\"aiModel\":null", "\"account\":null", "\"runNo\":0");
    JsonNode rpc = jsonMapper.readTree(humanBody);
    assertThat(rpc.has("error")).isFalse();
    assertThat(rpc.get("result").get("isError").asBoolean()).isFalse();
  }

  @Test
  void withoutAKeyMcpIsRejected() throws Exception {
    HttpResponse<String> anonymous = post(null, call("whoami"), Map.of());
    assertThat(anonymous.statusCode()).isEqualTo(401);
    assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"stocks\"");
    assertThat(jsonMapper.readTree(anonymous.body()).get("code").asString()).isEqualTo("UNAUTHENTICATED");

    HttpResponse<String> badKey = post("stk_aaaaaaaaaaaa_" + "x".repeat(43), call("whoami"), Map.of());
    assertThat(badKey.statusCode()).isEqualTo(401);
    assertThat(jsonMapper.readTree(badKey.body()).get("code").asString()).isEqualTo("INVALID_API_KEY");

    fx.revokeApiKey(botKey);
    HttpResponse<String> revoked = post(botKey, call("whoami"), Map.of());
    assertThat(revoked.statusCode()).isEqualTo(401);
    assertThat(jsonMapper.readTree(revoked.body()).get("code").asString()).isEqualTo("API_KEY_REVOKED");
  }

  @Test
  void originIsCheckedAgainstThePublicUrlOnlyWhenPresent() throws Exception {
    assertThat(post(botKey, call("whoami"), Map.of("Origin", "https://evil.example")).statusCode()).isEqualTo(403);
    assertThat(post(botKey, call("whoami"), Map.of("Origin", McpConfig.origin(properties.publicUrl())))
        .statusCode()).isEqualTo(200);
    assertThat(post(botKey, call("whoami"), Map.of()).statusCode()).isEqualTo(200);
  }

  @Test
  void statelessTransportRefusesGetAndAcceptsNotifications() throws Exception {
    HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Authorization", "Bearer " + botKey).header("Accept", "text/event-stream").GET().build();
    assertThat(HttpClient.newHttpClient().send(get, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(405);
    HttpResponse<String> notification = post(botKey,
        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of());
    assertThat(notification.statusCode()).isEqualTo(202);
  }

  // ------------------------------------------------------------------------------------------- wiring (§28 #20)

  @Test
  void mcpServerJsonMapperIsBootsMapperAndTheTransportIsOurs() {
    assertThat(context.getBean("mcpServerJsonMapper")).isSameAs(jsonMapper);
    assertThat(context.getBeanFactory().getBeanDefinition("mcpServerJsonMapper").getFactoryBeanName())
        .isEqualTo("mcpConfig");
    assertThat(context.getBeanNamesForType(JsonMapper.class)).contains("jacksonJsonMapper", "mcpServerJsonMapper");
    assertThat(context.getBeanNamesForType(WebMvcStatelessServerTransport.class))
        .containsExactly("webMvcStatelessServerTransport");
    assertThat(context.getBeanFactory().getBeanDefinition("webMvcStatelessServerTransport").getFactoryBeanName())
        .isEqualTo("mcpConfig");
  }

  // ------------------------------------------------------------------------------------------- helpers

  McpSyncClient client(String key) {
    HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
        .builder("http://127.0.0.1:" + port)
        .endpoint("/mcp")
        .requestBuilder(HttpRequest.newBuilder()
            .header("Authorization", "Bearer " + key)
            .header(McpClientNames.HEADER, "mcp-server-it"))
        .build();
    return McpClient.sync(transport)
        .clientInfo(McpSchema.Implementation.builder("stocks-mcp-it", "1.0.0").build())
        .requestTimeout(Duration.ofSeconds(20))
        .build();
  }

  static String call(String tool) {
    return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
        + "\",\"arguments\":{}}}";
  }

  HttpResponse<String> post(String keyOrNull, String json, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(json));
    if (keyOrNull != null) {
      b.header("Authorization", "Bearer " + keyOrNull);
    }
    headers.forEach(b::header);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }
}
