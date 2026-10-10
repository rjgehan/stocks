package com.stocks.mcp.tools;

import com.stocks.common.web.Page;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.mcp.tools.McpOutputs.MarkedDto;
import com.stocks.realtime.NotificationService;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Notification tools (§20.3 #22, #38; scope {@code account:read}, like {@code /me/notifications}): what happened to
 * your account while you were away (fills, expiries, cancellations you did not ask for, deposits, good-faith
 * violations, freezes, resets), from WS5's {@link NotificationService}.
 */
@Component
public class NotificationTools implements McpToolSet {

  private final NotificationService notifications;

  public NotificationTools(NotificationService notifications) {
    this.notifications = notifications;
  }

  @McpTool(name = "get_notifications", title = "Notifications",
      description = "Your notifications, newest first: fills, partial fills, expired orders, orders cancelled by the "
          + "system or the admin, deposits and withdrawals, good-faith violations, freezes and resets. Each has a "
          + "kind, title, body, payload (ids such as orderId) and readAt (null = unread). For the next page, pass "
          + "nextCursor as cursor.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Notifications", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public Page<NotificationDto> get_notifications(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Only unread notifications (default false)")
      Boolean unread_only,
      @McpToolParam(required = false, description = "1-100, default 50") Integer limit,
      @McpToolParam(required = false, description = "nextCursor of the previous page") String cursor) {
    long playerId = McpArgs.caller(ctx).playerId();
    int n = McpArgs.limit(limit, 50, 1, 100, "limit");
    return notifications.list(playerId, Boolean.TRUE.equals(unread_only), McpArgs.cursor(cursor), n);
  }

  @McpTool(name = "mark_notifications_read", title = "Mark notifications read",
      description = "Marks notifications read: pass exactly one of ids (notification ids, ntf_…, up to 200) and "
          + "all=true. Returns how many changed from unread to read.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Mark notifications read", readOnlyHint = false,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public MarkedDto mark_notifications_read(McpTransportContext ctx,
      @McpToolParam(required = false, description = "Notification ids (ntf_…)") List<String> ids,
      @McpToolParam(required = false, description = "true to mark every notification read") Boolean all) {
    long playerId = McpArgs.caller(ctx).playerId();
    return new MarkedDto(notifications.markRead(playerId, ids, all));
  }
}
