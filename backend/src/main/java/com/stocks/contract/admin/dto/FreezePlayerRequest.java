package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /admin/players/{handle}/freeze} (§21.1, §10.5). {@code revokeKeys} default false. */
public record FreezePlayerRequest(@NotBlank String reason, Boolean revokeKeys) {}
