package com.stocks.contract.push.dto;

/** Phase 2 §18.3 {@code PushPreferences} (§19.2): which categories reach the player's devices; all default on. */
public record PushPreferencesDto(boolean fills, boolean alerts, boolean money, boolean account, boolean league) {}
