package com.stocks.realtime.web;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.realtime.dto.MarkNotificationsReadRequest;
import com.stocks.contract.realtime.dto.NotificationDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.realtime.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 notifications: fills, cancellations the player did not ask for, expiries, admin deposits, good-faith
 * violations, freezes and resets. Any channel with {@code account:read} (sessions hold every scope; route rule in
 * {@code SecurityConfig}). New ones also arrive live on {@code /user/queue/notifications} (§19).
 */
@RestController
@RequestMapping("/api/v1/me/notifications")
@Tag(name = "notifications", description = "The player's notifications")
public class NotificationController {

  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping
  @Operation(summary = "List notifications", description = "Newest first. unreadOnly keeps unread ones; page with "
      + "the opaque nextCursor (§6.6).")
  public Page<NotificationDto> list(
      @Parameter(description = "Only unread notifications") @RequestParam(defaultValue = "false") boolean unreadOnly,
      @Parameter(description = "Opaque cursor from a previous page") @RequestParam(required = false) String cursor,
      @Parameter(description = "1-200, default 50") @RequestParam(required = false) Integer limit) {
    return notifications.list(CurrentPlayer.require().playerId(), unreadOnly, Cursor.parse(cursor),
        Page.clampLimit(limit));
  }

  @PostMapping("/read")
  @Operation(summary = "Mark notifications read", description = "Exactly one of ids (up to 200 notification ids) "
      + "and all=true. Ids that are not yours or already read are ignored.")
  public ResponseEntity<Void> markRead(@RequestBody MarkNotificationsReadRequest request) {
    notifications.markRead(CurrentPlayer.require().playerId(), request == null ? null : request.ids(),
        request == null ? null : request.all());
    return ResponseEntity.noContent().build();
  }
}
