package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /admin/players/{handle}/live-view} (§21.1): {@code reason} 5..200 characters. */
public record LiveViewRequest(@NotBlank String reason) {}
