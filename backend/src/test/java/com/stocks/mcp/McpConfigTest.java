package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.web.RequestIds;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.security.PlayerAuthenticationToken;
import com.stocks.testkit.TestPrincipals;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.ServerRequest;

/** §20.1 {@code McpConfig} pieces that need no server: the context extractor, the Origin value, the instructions. */
class McpConfigTest {

  static final PlayerPrincipal BOT = TestPrincipals.apiKey(42, "ply_01", "claude", PlayerKind.AI, Scopes.ALL, 9);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  static MockHttpServletRequest mcpPost() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("User-Agent", "agent-runner/claude node");
    request.setAttribute(RequestIds.ATTRIBUTE, "req_01TEST");
    return request;
  }

  static McpTransportContext extract(MockHttpServletRequest request) {
    return McpConfig.contextExtractor(new McpClientNames()).extract(ServerRequest.create(request, List.of()));
  }

  @Test
  void extractorPutsTheAuthenticatedPlayerAndRequestMetaIntoTheContext() {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(BOT));
    MockHttpServletRequest request = mcpPost();
    request.addHeader(McpClientNames.HEADER, "claude-code");

    McpTransportContext ctx = extract(request);
    SecurityContextHolder.clearContext();   // a tool running off the servlet thread sees no SecurityContext

    assertThat(CurrentPlayer.require(ctx)).isSameAs(BOT);
    RequestMeta meta = CurrentPlayer.requestMeta(ctx).orElseThrow();
    assertThat(meta.channel()).isEqualTo(Channel.MCP_KEY);
    assertThat(meta.requestId()).isEqualTo("req_01TEST");
    assertThat(meta.ip()).isEqualTo("10.1.2.3");
    assertThat(meta.userAgent()).isEqualTo("agent-runner/claude node");
    assertThat(meta.mcpClient()).isEqualTo("claude-code");
    assertThat(meta.mcpTool()).isNull();   // set per call by the tool handler
  }

  @Test
  void clientNameFallsBackToTheUserAgentAndIsClipped() {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(BOT));
    assertThat(CurrentPlayer.requestMeta(extract(mcpPost())).orElseThrow().mcpClient())
        .isEqualTo("agent-runner/claude node");

    MockHttpServletRequest longName = mcpPost();
    longName.addHeader(McpClientNames.HEADER, "x".repeat(500));
    assertThat(CurrentPlayer.requestMeta(extract(longName)).orElseThrow().mcpClient()).hasSize(120);
  }

  @Test
  void anAnonymousRequestCarriesNoPlayer() {
    McpTransportContext ctx = extract(mcpPost());
    assertThat(CurrentPlayer.current(ctx)).isEmpty();
    assertThat(CurrentPlayer.requestMeta(ctx).orElseThrow().channel()).isEqualTo(Channel.WEB);
  }

  @Test
  void originIsTheSchemeHostAndPortOfThePublicUrl() {
    assertThat(McpConfig.origin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    assertThat(McpConfig.origin("https://Stocks.Example.com/")).isEqualTo("https://stocks.example.com");
    assertThat(McpConfig.origin(" https://stocks.example.com:8443/app ")).isEqualTo("https://stocks.example.com:8443");
    assertThatThrownBy(() -> McpConfig.origin("stocks.example.com")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void instructionsComeFromTheClasspathUnlessConfigured() {
    String text = McpConfig.instructions();
    assertThat(text).contains("get_rules", "client_order_id", "rationale", "decimal strings", "untrusted",
        "RATE_LIMITED", "retry_after_s", "ENGINE_TIMEOUT", "preview_order");
    // §17.8 (review): other players' names are plain fields, so the instructions name them as data too
    assertThat(text).contains("`handle` and `displayName`");

    BeanPostProcessor loader = McpConfig.mcpInstructionsLoader();
    McpServerProperties unset = new McpServerProperties();
    loader.postProcessAfterInitialization(unset, "props");
    assertThat(unset.getInstructions()).isEqualTo(text);

    McpServerProperties configured = new McpServerProperties();
    configured.setInstructions("custom");
    loader.postProcessAfterInitialization(configured, "props");
    assertThat(configured.getInstructions()).isEqualTo("custom");
  }
}
