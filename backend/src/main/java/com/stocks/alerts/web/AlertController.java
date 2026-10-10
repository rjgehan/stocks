package com.stocks.alerts.web;

import com.stocks.alerts.AlertService;
import com.stocks.common.domain.AlertStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.alerts.dto.AlertDto;
import com.stocks.contract.alerts.dto.CreateAlertRequest;
import com.stocks.contract.alerts.dto.UpdateAlertRequest;
import com.stocks.contract.security.CurrentPlayer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.2 {@code /alerts} (Phase 2, §19.1): the caller's price alerts. Reading needs {@code account:read}, writing
 * {@code trade} (the security chain checks it, §17.1). Another player's alert id is {@code NOT_FOUND}. Alerts only
 * notify; they never place, change or cancel an order.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "alerts", description = "The caller's price alerts")
public class AlertController {

  private final AlertService alerts;

  public AlertController(AlertService alerts) {
    this.alerts = alerts;
  }

  private static long me() {
    return CurrentPlayer.require().playerId();
  }

  @GetMapping
  @Operation(summary = "The caller's price alerts", description = "ACTIVE, PAUSED, TRIGGERED, EXPIRED in that order, "
      + "newest first within each; optionally of one status and/or symbol.")
  public List<AlertDto> listAlerts(@RequestParam(name = "status", required = false) AlertStatus status,
                                   @RequestParam(name = "symbol", required = false) String symbol) {
    return alerts.list(me(), status, symbol);
  }

  @PostMapping
  @Operation(summary = "Create a price alert", description = "201 with the new alert; 200 with the existing one when "
      + "an identical ACTIVE or PAUSED alert exists (symbol, condition, threshold, repeat). A price threshold is a "
      + "price; a change threshold is a fraction like Quote.changePct (\"0.05\" = +5%). At most 50 active or paused "
      + "alerts (TOO_MANY_ALERTS).")
  public ResponseEntity<AlertDto> createAlert(@Valid @RequestBody CreateAlertRequest request) {
    AlertService.Created c = alerts.create(me(), request);
    return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(c.alert());
  }

  @PutMapping("/{alertId}")
  @Operation(summary = "Edit a price alert", description = "Replaces condition, threshold, repeat, expiry and note, "
      + "and re-arms it; a TRIGGERED or EXPIRED alert becomes ACTIVE again, a PAUSED one stays PAUSED.")
  public AlertDto updateAlert(@PathVariable("alertId") String alertId,
                              @Valid @RequestBody UpdateAlertRequest request) {
    return alerts.update(me(), alertId, request);
  }

  @PostMapping("/{alertId}/pause")
  @Operation(summary = "Pause a price alert", description = "A paused alert is kept but never fires.")
  public AlertDto pauseAlert(@PathVariable("alertId") String alertId) {
    return alerts.pause(me(), alertId);
  }

  @PostMapping("/{alertId}/resume")
  @Operation(summary = "Resume a paused price alert", description = "Re-arms it.")
  public AlertDto resumeAlert(@PathVariable("alertId") String alertId) {
    return alerts.resume(me(), alertId);
  }

  @DeleteMapping("/{alertId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a price alert")
  public void deleteAlert(@PathVariable("alertId") String alertId) {
    if (!alerts.delete(me(), alertId)) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No such alert.");
    }
  }
}
