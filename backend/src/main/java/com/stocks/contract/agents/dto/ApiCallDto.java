package com.stocks.contract.agents.dto;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import java.time.Instant;

/**
 * §17.10 {@code ApiCall}: one hour of one key's calls to one endpoint with one outcome ("Recent API calls").
 * {@code outcome} is the HTTP status for REST and {@code OK} or the error code for MCP; {@code ok} is 2xx/3xx or OK.
 */
public record ApiCallDto(String id, Instant hourStart, String handle, String displayName, PlayerKind playerKind,
                         String keyId, String keyName, Channel channel, String endpoint, String outcome, boolean ok,
                         int calls, long avgMs, long maxMs, Instant firstAt, Instant lastAt, String lastIp,
                         String client) {}
