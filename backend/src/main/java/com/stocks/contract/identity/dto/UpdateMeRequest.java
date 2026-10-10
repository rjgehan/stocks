package com.stocks.contract.identity.dto;

/** {@code PATCH /me} (§18.1): {@code {displayName?, preferences?: Partial<Preferences>}}; null = unchanged. */
public record UpdateMeRequest(String displayName, UpdatePreferencesRequest preferences) {}
