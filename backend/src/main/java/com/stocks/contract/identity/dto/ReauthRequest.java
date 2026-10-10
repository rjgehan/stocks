package com.stocks.contract.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/reauth} (§18.1): refreshes {@code authenticatedAt}. */
public record ReauthRequest(@NotBlank String password) {}
