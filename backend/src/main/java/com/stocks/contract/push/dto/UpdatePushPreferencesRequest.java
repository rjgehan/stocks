package com.stocks.contract.push.dto;

/** Phase 2 {@code PATCH /me/push/preferences} (§18.1): {@code Partial<PushPreferences>}; null members are unchanged. */
public record UpdatePushPreferencesRequest(Boolean fills, Boolean alerts, Boolean money, Boolean account,
                                           Boolean league) {}
