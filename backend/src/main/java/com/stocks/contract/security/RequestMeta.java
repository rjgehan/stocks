package com.stocks.contract.security;

import com.stocks.common.domain.Channel;
import com.stocks.common.web.Text;

/**
 * Per-request metadata for audit rows and order provenance (§5.3, §6.6). Client-controlled strings are bounded here,
 * once: {@code User-Agent} 300, MCP client name 120, MCP tool 64 characters, control characters stripped
 * ({@link Text#clip}). Writers never see an unbounded header.
 */
public record RequestMeta(String requestId, Channel channel, String ip, String userAgent,
                          String mcpClient, String mcpTool) {

  public static final int MAX_USER_AGENT = 300;
  public static final int MAX_MCP_CLIENT = 120;
  public static final int MAX_MCP_TOOL = 64;

  public RequestMeta {
    userAgent = Text.clip(userAgent, MAX_USER_AGENT);
    mcpClient = Text.clip(mcpClient, MAX_MCP_CLIENT);
    mcpTool = Text.clip(mcpTool, MAX_MCP_TOOL);
  }

  /** Metadata for work the system does on its own (jobs, the engine): channel {@code SYSTEM}. */
  public static RequestMeta system(String requestId) {
    return new RequestMeta(requestId, Channel.SYSTEM, null, null, null, null);
  }
}
