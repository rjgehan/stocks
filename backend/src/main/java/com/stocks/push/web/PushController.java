package com.stocks.push.web;

import com.stocks.contract.push.dto.PushDeviceDto;
import com.stocks.contract.push.dto.PushEndpointRequest;
import com.stocks.contract.push.dto.PushPreferencesDto;
import com.stocks.contract.push.dto.PushSettingsDto;
import com.stocks.contract.push.dto.PushSubscriptionRequest;
import com.stocks.contract.push.dto.PushSyncDto;
import com.stocks.contract.push.dto.PushTestResultDto;
import com.stocks.contract.push.dto.UpdatePushPreferencesRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.push.PushService;
import com.stocks.push.PushSubscriptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 {@code /me/push/**} (Phase 2, §19.2): Web Push on the caller's devices. Browser sessions only (the chain's
 * {@code /me/**} rule): bots never have browser subscriptions. A device is bound to the HTTP session that registered
 * it, so signing out of that session removes it; the endpoint and keys never leave the server.
 */
@RestController
@RequestMapping("/api/v1/me/push")
@Tag(name = "push", description = "Web Push notifications on the caller's devices")
public class PushController {

  private final PushService push;

  public PushController(PushService push) {
    this.push = push;
  }

  private static long me() {
    return CurrentPlayer.require().playerId();
  }

  /** SHA-256 of the caller's HTTP session id (null without a session). */
  static String sessionHash(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    return session == null ? null : PushSubscriptions.sha256(session.getId());
  }

  @GetMapping
  @Operation(summary = "Push settings", description = "Whether Web Push works on this server, the VAPID public key "
      + "to subscribe with, the category preferences and the caller's devices (current = this session's).")
  public PushSettingsDto getPushSettings(HttpServletRequest request) {
    return push.settings(me(), sessionHash(request));
  }

  @PatchMapping("/preferences")
  @Operation(summary = "Change push preferences", description = "Partial: null members are unchanged. Categories: "
      + "fills (fills, cancellations, expiries), alerts (price alerts), money (admin deposits, withdrawals, "
      + "adjustments), account (violations, restrictions, corporate actions, resets), league (a new weekly leader).")
  public PushPreferencesDto updatePushPreferences(@RequestBody UpdatePushPreferencesRequest request) {
    return push.updatePreferences(me(), request);
  }

  @PostMapping("/subscriptions")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register this browser for push", description = "The browser's PushSubscription (endpoint "
      + "and keys, base64url). A subscription belongs to one player: registering it from another player's session "
      + "moves it. At most 10 devices per player (the oldest is dropped).")
  public PushDeviceDto createPushSubscription(@Valid @RequestBody PushSubscriptionRequest body,
                                              HttpServletRequest request) {
    return push.subscribe(me(), sessionHash(request), body, request.getHeader(HttpHeaders.USER_AGENT));
  }

  @PostMapping("/sync")
  @Operation(summary = "Check this browser's push registration", description = "registered=false: the server does "
      + "not know this subscription for the caller (removed at sign-out or by a password change, or the device "
      + "changed hands); the SPA then registers it again if the player turned push on on this device, otherwise it "
      + "unsubscribes the browser. Re-binds the registration to the current session.")
  public PushSyncDto syncPushSubscription(@Valid @RequestBody PushEndpointRequest body, HttpServletRequest request) {
    return push.sync(me(), sessionHash(request), body.endpoint());
  }

  @PostMapping("/unsubscribe")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Turn push off on this browser")
  public void unsubscribePush(@Valid @RequestBody PushEndpointRequest body) {
    push.unsubscribe(me(), body.endpoint());
  }

  @DeleteMapping("/subscriptions/{deviceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Remove one of the caller's push devices")
  public void deletePushSubscription(@PathVariable("deviceId") String deviceId) {
    push.deleteDevice(me(), deviceId);
  }

  @PostMapping("/test")
  @Operation(summary = "Send a test notification", description = "To every device of the caller; waits up to 15 s "
      + "for the push services. One call per 10 seconds.")
  public PushTestResultDto testPush() {
    return push.test(me());
  }
}
