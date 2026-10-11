package com.stocks.mcp.tools;

import com.stocks.alerts.AlertService;
import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import com.stocks.contract.alerts.dto.AlertDto;
import com.stocks.contract.alerts.dto.CreateAlertRequest;
import com.stocks.mcp.tools.McpOutputs.DeletedDto;
import io.modelcontextprotocol.common.McpTransportContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Price-alert tools (§20.3 #49–51, Phase 2): list with {@code account:read}, create and delete with {@code trade}
 * (as REST {@code /alerts}, §17.1), through the same {@link AlertService}. An alert only notifies (a
 * {@code PRICE_ALERT} notification, read with {@code get_notifications}); it never trades.
 */
@Component
public class AlertTools implements McpToolSet {

  /** {@code list_alerts}: {@code {alerts: Alert[]}} (MCP results are objects, §20.2). */
  public record AlertsDto(List<AlertDto> alerts) {}

  private final AlertService alerts;

  public AlertTools(AlertService alerts) {
    this.alerts = alerts;
  }

  @McpTool(name = "list_alerts", title = "Price alerts",
      description = "Your price alerts: ACTIVE, PAUSED, TRIGGERED, EXPIRED in that order, newest first within each. "
          + "Optionally only one status and/or one symbol. A fired alert shows triggerCount, lastTriggeredAt and "
          + "lastTriggerPrice; the PRICE_ALERT notification it wrote is in get_notifications.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Price alerts", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public AlertsDto list_alerts(McpTransportContext ctx,
      @McpToolParam(required = false, description = "ACTIVE, PAUSED, TRIGGERED or EXPIRED") AlertStatus status,
      @McpToolParam(required = false, description = "Only alerts on this ticker") String symbol) {
    long playerId = McpArgs.caller(ctx).playerId();
    return new AlertsDto(alerts.list(playerId, status, McpArgs.optionalSymbol(symbol, "symbol")));
  }

  @McpTool(name = "create_alert", title = "Create a price alert",
      description = "Creates a price alert that notifies you (a PRICE_ALERT notification) when its condition holds "
          + "on a live price; it never places or changes an order. condition: PRICE_GTE / PRICE_LTE (threshold is a "
          + "price, e.g. \"230.00\") or CHANGE_PCT_GTE / CHANGE_PCT_LTE (threshold is the change from the previous "
          + "close as a fraction like Quote.changePct: \"0.05\" = +5%, \"-0.03\" = -3%). repeat=false (default) "
          + "fires once; repeat=true fires again after the price moves 0.5% back (and at most every 15 minutes). "
          + "Idempotent: an identical active or paused alert is returned instead of a duplicate. At most 50 active "
          + "or paused alerts (TOO_MANY_ALERTS).",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Create a price alert", readOnlyHint = false,
          destructiveHint = false, idempotentHint = true, openWorldHint = false))
  public AlertDto create_alert(McpTransportContext ctx,
      @McpToolParam(description = "Ticker, e.g. \"AAPL\"") String symbol,
      @McpToolParam(description = "PRICE_GTE, PRICE_LTE, CHANGE_PCT_GTE or CHANGE_PCT_LTE") AlertCondition condition,
      @McpToolParam(description = "Decimal string: a price, or a change fraction (\"0.05\" = +5%)")
      BigDecimal threshold,
      @McpToolParam(required = false, description = "Fire again after re-arming (default false: once)")
      Boolean repeat,
      @McpToolParam(required = false, description = "Expiry (ISO-8601 instant, within 365 days)") Instant expires_at,
      @McpToolParam(required = false, description = "A private note, at most 140 characters") String note) {
    long playerId = McpArgs.caller(ctx).playerId();
    String sym = McpArgs.symbol(symbol, "symbol");
    return alerts.create(playerId, new CreateAlertRequest(sym, condition, threshold, repeat, expires_at, note))
        .alert();
  }

  @McpTool(name = "delete_alert", title = "Delete a price alert",
      description = "Deletes one of your price alerts by its id (alr_...). Returns deleted=false if there was no such "
          + "alert.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Delete a price alert", readOnlyHint = false,
          destructiveHint = true, idempotentHint = true, openWorldHint = false))
  public DeletedDto delete_alert(McpTransportContext ctx,
      @McpToolParam(description = "The alert id (alr_...)") String alert_id) {
    long playerId = McpArgs.caller(ctx).playerId();
    return new DeletedDto(alerts.delete(playerId, McpArgs.text(alert_id, 64, "alert_id")));
  }
}
