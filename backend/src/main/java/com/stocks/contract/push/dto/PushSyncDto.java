package com.stocks.contract.push.dto;

/** Phase 2 §18.3 {@code PushSync} ({@code POST /me/push/sync}, §19.2): is this browser's subscription registered? */
public record PushSyncDto(boolean registered, PushDeviceDto device) {}
