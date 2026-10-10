package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /admin/players/{handle}/password} (§21.1, humans). */
public record SetPasswordRequest(@NotBlank String newPassword) {}
