package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.TestPrincipals;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** §20.1/§20.2: argument binding, Boot-mapper results (structured + text) and error results of a tool call. */
class McpToolHandlerTest {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();
  static final PlayerPrincipal BOT = TestPrincipals.apiKey(42, "ply_01", "claude", PlayerKind.AI, Scopes.ALL, 9);
  static final RequestMeta META = new RequestMeta("req_1", Channel.MCP_KEY, "127.0.0.1", "ua", "claude-code", null);

  public record EchoDto(BigDecimal amount, String note, String handle, String tool, String client) {}

  public static class Tools {
    @McpTool(name = "whoami", generateOutputSchema = true)
    public EchoDto echo(McpTransportContext ctx, @McpToolParam BigDecimal amount,
                        @McpToolParam(required = false) String note) {
      if ("api".equals(note)) {
        throw ApiException.of(ErrorCode.RATE_LIMITED, "Slow down.").withHint("Wait.").withRetryAfter(7);
      }
      if ("boom".equals(note)) {
        throw new IllegalStateException("secret detail");
      }
      PlayerPrincipal caller = CurrentPlayer.require(ctx);
      RequestMeta meta = CurrentPlayer.requestMeta(ctx).orElseThrow();
      return new EchoDto(amount, note, caller.handle(), meta.mcpTool(), meta.mcpClient());
    }
  }

  SyncToolSpecification spec;

  @BeforeEach
  void setUp() throws Exception {
    SecurityContextHolder.clearContext();   // the caller must come from the transport context alone
    spec = McpToolCatalog.specification(new Tools(), Tools.class.getMethod("echo", McpTransportContext.class,
        BigDecimal.class, String.class), MAPPER);
  }

  CallToolResult call(Map<String, Object> args) {
    return spec.callHandler().apply(CurrentPlayer.mcpContext(BOT, META), new CallToolRequest("whoami", args));
  }

  static JsonNode text(CallToolResult r) {
    assertThat(r.content()).hasSize(1);
    return MAPPER.readTree(((TextContent) r.content().get(0)).text());
  }

  @Test
  void resultIsBootJsonAsStructuredContentAndText() {
    Map<String, Object> args = new HashMap<>();
    args.put("amount", "12.30");
    CallToolResult r = call(args);
    assertThat(r.isError()).isFalse();
    @SuppressWarnings("unchecked")
    Map<String, Object> structured = (Map<String, Object>) r.structuredContent();
    assertThat(structured).containsEntry("amount", "12.30").containsEntry("note", null)
        .containsEntry("handle", "claude").containsEntry("tool", "whoami").containsEntry("client", "claude-code");
    assertThat(text(r)).isEqualTo(MAPPER.valueToTree(structured));
  }

  @Test
  void decimalArgumentsMayBeNumbersAndNeverPassThroughDouble() {
    CallToolResult r = call(Map.of("amount", new BigDecimal("0.10000")));
    assertThat(text(r).get("amount").asString()).isEqualTo("0.10000");
    CallToolResult fromInt = call(Map.of("amount", 3));
    assertThat(text(fromInt).get("amount").asString()).isEqualTo("3");
  }

  @Test
  void missingOrInvalidArgumentsAreValidationErrors() {
    JsonNode missing = text(call(Map.of()));
    assertThat(missing.get("code").asString()).isEqualTo("VALIDATION_FAILED");
    assertThat(missing.get("fields").get(0).get("field").asString()).isEqualTo("amount");

    CallToolResult invalid = call(Map.of("amount", "twelve"));
    assertThat(invalid.isError()).isTrue();
    assertThat(text(invalid).get("code").asString()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void apiExceptionsKeepTheirCodeHintAndRetryAfter() {
    CallToolResult r = call(Map.of("amount", "1", "note", "api"));
    assertThat(r.isError()).isTrue();
    assertThat(r.structuredContent()).isNull();
    JsonNode body = text(r);
    assertThat(body.propertyNames()).containsExactly("code", "message", "hint", "fields", "retry_after_s");
    assertThat(body.get("code").asString()).isEqualTo("RATE_LIMITED");
    assertThat(body.get("message").asString()).isEqualTo("Slow down.");
    assertThat(body.get("hint").asString()).isEqualTo("Wait.");
    assertThat(body.get("retry_after_s").asLong()).isEqualTo(7);
  }

  @Test
  void unexpectedFailuresAreInternalErrorsWithoutDetails() {
    CallToolResult r = call(Map.of("amount", "1", "note", "boom"));
    assertThat(r.isError()).isTrue();
    JsonNode body = text(r);
    assertThat(body.get("code").asString()).isEqualTo("INTERNAL_ERROR");
    assertThat(body.toString()).doesNotContain("secret");
    assertThat(body.get("hint").isNull()).isTrue();
  }

  @Test
  void withoutACallerTheToolAnswersUnauthenticated() {
    CallToolResult r = spec.callHandler().apply(McpTransportContext.EMPTY,
        new CallToolRequest("whoami", Map.of("amount", "1")));
    assertThat(text(r).get("code").asString()).isEqualTo("UNAUTHENTICATED");
  }

  // ------------------------------------------------------------------------------------------- scopes (§20.3)

  public static class Counting {
    int calls;

    @McpTool(name = "get_account", generateOutputSchema = true)
    public EchoDto account(McpTransportContext ctx) {
      calls++;
      return new EchoDto(null, null, CurrentPlayer.require(ctx).handle(), null, null);
    }
  }

  @Test
  void aCallerWithoutTheToolsScopeGetsForbiddenScopeAndTheToolNeverRuns() throws Exception {
    Counting tools = new Counting();
    SyncToolSpecification account = McpToolCatalog.specification(tools,
        Counting.class.getMethod("account", McpTransportContext.class), MAPPER);
    PlayerPrincipal marketOnly = TestPrincipals.apiKey(42, "ply_01", "claude", PlayerKind.AI,
        Set.of(Scopes.MARKET_READ), 9);

    CallToolResult denied = account.callHandler().apply(CurrentPlayer.mcpContext(marketOnly, META),
        new CallToolRequest("get_account", Map.of()));
    assertThat(denied.isError()).isTrue();
    assertThat(text(denied).get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(text(denied).get("message").asString()).contains("account:read");

    CallToolResult anonymous = account.callHandler().apply(McpTransportContext.EMPTY,
        new CallToolRequest("get_account", Map.of()));
    assertThat(text(anonymous).get("code").asString()).isEqualTo("UNAUTHENTICATED");
    assertThat(tools.calls).isZero();

    CallToolResult allowed = account.callHandler().apply(CurrentPlayer.mcpContext(BOT, META),
        new CallToolRequest("get_account", Map.of()));
    assertThat(allowed.isError()).isFalse();
    assertThat(tools.calls).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------- tool rules

  public static class BadParameter {
    @McpTool(name = "get_rules", generateOutputSchema = true)
    public EchoDto rules(McpTransportContext ctx, String unannotated) {
      return null;
    }
  }

  public static class NoOutputSchema {
    @McpTool(name = "get_rules")
    public EchoDto rules(McpTransportContext ctx) {
      return null;
    }
  }

  @Test
  void statelessToolsTakeOnlyTheContextAndToolParams() {
    assertThatThrownBy(() -> McpToolCatalog.specifications(List.of(new BadParameter()), MAPPER))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("@McpToolParam");
    assertThatThrownBy(() -> McpToolCatalog.specifications(List.of(new NoOutputSchema()), MAPPER))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("generateOutputSchema");
  }
}
