package com.stocks.contract.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.Channel;
import org.junit.jupiter.api.Test;

/** §6.6: client-controlled strings are bounded once, in the compact constructor. */
class RequestMetaTest {

  @Test
  void clipsAndStripsClientControlledStrings() {
    String ua = "Mozilla\u0000/5.0 " + "x".repeat(10_000);
    RequestMeta m = new RequestMeta("req_1", Channel.MCP_KEY, "10.0.0.1", ua, "c\nlaude-" + "y".repeat(500),
        "place_order" + "z".repeat(100));
    assertThat(m.userAgent()).hasSize(RequestMeta.MAX_USER_AGENT).startsWith("Mozilla/5.0 x");
    assertThat(m.mcpClient()).hasSize(RequestMeta.MAX_MCP_CLIENT).startsWith("claude-");
    assertThat(m.mcpTool()).hasSize(RequestMeta.MAX_MCP_TOOL).startsWith("place_order");
    assertThat(m.ip()).isEqualTo("10.0.0.1");
  }

  @Test
  void nullsStayNullAndSystemMetaHasTheSystemChannel() {
    RequestMeta m = RequestMeta.system("req_2");
    assertThat(m.channel()).isEqualTo(Channel.SYSTEM);
    assertThat(m.userAgent()).isNull();
    assertThat(m.mcpClient()).isNull();
  }
}
