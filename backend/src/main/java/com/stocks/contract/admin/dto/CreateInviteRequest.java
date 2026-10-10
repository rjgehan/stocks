package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /admin/invites} (§21.1): {@code maxUses} 1..50, {@code expiresInHours} 1..720. */
public record CreateInviteRequest(@NotNull Integer maxUses, @NotNull Integer expiresInHours) {}
