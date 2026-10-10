package com.stocks.contract.identity.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * {@code POST /me/api-keys} and {@code POST /admin/players/{handle}/api-keys} (§18.1, §21.1). {@code scopes} null =
 * the endpoint's default (personal: read-only; admin: all four). {@code expiresInDays} 1..365 or null (no expiry).
 */
public record CreateApiKeyRequest(@NotBlank String name, List<String> scopes, Integer expiresInDays) {}
