package com.stocks.contract.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/login} (§18.1). */
public record LoginRequest(@NotBlank String handle, @NotBlank String password) {}
