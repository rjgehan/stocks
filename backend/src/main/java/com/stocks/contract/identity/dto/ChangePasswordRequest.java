package com.stocks.contract.identity.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /me/password} (§18.1, §17.3). */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {}
