package com.stocks.contract.agents;

import com.stocks.common.domain.Channel;
import com.stocks.contract.security.PlayerPrincipal;

/**
 * The API access log (§17.10, WS5's {@code apilog.ApiAccessLog}): counts one call of an API key in its hourly bucket.
 * The REST filter and the MCP tool handler call it; it never blocks on the database and never throws.
 */
public interface ApiAccessRecorder {

  /**
   * Records one call when {@code caller} authenticated with an API key (anything else is ignored).
   *
   * @param channel    {@code REST_KEY} or {@code MCP_KEY}
   * @param endpoint   the route template ({@code GET /api/v1/orders/{orderId}}) or the MCP tool name
   * @param outcome    the HTTP status ({@code "200"}) or {@code OK} / the MCP error code
   * @param durationMs how long the call took
   * @param ip         the client IP (may be null)
   * @param client     the {@code User-Agent} or MCP client name (may be null)
   */
  void record(PlayerPrincipal caller, Channel channel, String endpoint, String outcome, long durationMs, String ip,
              String client);
}
