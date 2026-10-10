package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /admin/engine/halt} (§21.1). */
public record HaltEngineRequest(@NotBlank String reason) {}
