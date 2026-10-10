package com.stocks.contract.admin.dto;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import java.time.Instant;
import java.util.Map;

/** §21.1 {@code AuditRow} ({@code audit_log}, §17.7). {@code actor} is a handle; {@code detail} is the stored JSON. */
public record AuditRowDto(long id, Instant occurredAt, String category, String action, String outcome, String actor,
                          PlayerKind actorKind, Channel channel, String apiKeyId, String mcpClient, String mcpTool,
                          String ip, String requestId, String targetType, String targetId,
                          Map<String, Object> detail) {}
