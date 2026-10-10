package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.TestTimes;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** §20.1 MCP client name: header, then the last {@code initialize} of the key, then {@code User-Agent}. */
class McpClientNamesTest {

  static PlayerPrincipal keyPrincipal(Long apiKeyId) {
    return new PlayerPrincipal(1, "ply_1", "claude", PlayerKind.AI, PlayerRole.PLAYER, Scopes.ALL,
        apiKeyId == null ? AuthMethod.SESSION : AuthMethod.API_KEY, apiKeyId, null, TestTimes.START);
  }

  @Test
  void headerThenRememberedInitializeThenUserAgent() {
    McpClientNames names = new McpClientNames();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
    request.addHeader("User-Agent", "node");
    PlayerPrincipal key = keyPrincipal(42L);

    assertThat(names.resolve(request, key)).isEqualTo("node");
    names.rememberInitialize(key, "claude-code/2.1.0");
    assertThat(names.resolve(request, key)).isEqualTo("claude-code/2.1.0");
    assertThat(names.resolve(request, keyPrincipal(43L))).as("per key").isEqualTo("node");

    request.addHeader(McpClientNames.HEADER, " agent-runner/claude ");
    assertThat(names.resolve(request, key)).isEqualTo("agent-runner/claude");
  }

  @Test
  void sessionsAreNotRemembered() {
    McpClientNames names = new McpClientNames();
    PlayerPrincipal session = keyPrincipal(null);
    names.rememberInitialize(session, "inspector/2");
    assertThat(names.resolve(new MockHttpServletRequest("POST", "/mcp"), session)).isNull();
  }

  @Test
  void clientNamesAreCleanedAndClipped() {
    assertThat(McpClientNames.clientName("claude-code", "2.1.0")).isEqualTo("claude-code/2.1.0");
    assertThat(McpClientNames.clientName("openai-mcp", null)).isEqualTo("openai-mcp");
    assertThat(McpClientNames.clientName("bad\nname", " ")).isEqualTo("badname");
    assertThat(McpClientNames.clientName(" ", "1")).isNull();
    assertThat(McpClientNames.clientName("x".repeat(200), "1")).hasSize(McpClientNames.MAX_CHARS);
  }

  @Test
  void initializeDetectionScansBytes() {
    byte[] needle = "\"initialize\"".getBytes(StandardCharsets.UTF_8);
    assertThat(McpInitializeCapture.contains("{\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8),
        needle)).isTrue();
    assertThat(McpInitializeCapture.contains("{\"method\":\"tools/call\"}".getBytes(StandardCharsets.UTF_8),
        needle)).isFalse();
    assertThat(McpInitializeCapture.contains(new byte[0], needle)).isFalse();
  }
}
