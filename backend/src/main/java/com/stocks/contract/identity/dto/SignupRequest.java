package com.stocks.contract.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/signup} (§18.1). Password strength is {@code WEAK_PASSWORD} (422), checked by the service. */
public record SignupRequest(@NotBlank String inviteCode, @NotBlank String handle, @NotBlank String displayName,
                            @NotBlank String password) {}
