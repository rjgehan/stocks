package com.stocks.contract.push.dto;

import java.util.List;

/**
 * Phase 2 §18.3 {@code PushSettings} ({@code GET /me/push}, §19.2): whether Web Push works on this server, the VAPID
 * public key the browser subscribes with (base64url, null when unavailable), the preferences and the devices.
 */
public record PushSettingsDto(boolean available, String vapidPublicKey, PushPreferencesDto preferences,
                              List<PushDeviceDto> devices) {}
