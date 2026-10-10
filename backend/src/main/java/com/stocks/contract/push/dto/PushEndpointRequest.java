package com.stocks.contract.push.dto;

import jakarta.validation.constraints.NotBlank;

/** Phase 2 {@code POST /me/push/sync} and {@code /me/push/unsubscribe} (§18.1): this browser's push endpoint. */
public record PushEndpointRequest(@NotBlank String endpoint) {}
