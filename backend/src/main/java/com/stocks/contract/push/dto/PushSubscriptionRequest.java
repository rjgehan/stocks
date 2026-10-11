package com.stocks.contract.push.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Phase 2 {@code POST /me/push/subscriptions} (§18.1, §19.2): the browser's {@code PushSubscription}, flattened
 * ({@code endpoint}, {@code keys.p256dh}, {@code keys.auth}, base64url).
 */
public record PushSubscriptionRequest(@NotBlank String endpoint, @NotBlank String p256dh, @NotBlank String auth) {}
