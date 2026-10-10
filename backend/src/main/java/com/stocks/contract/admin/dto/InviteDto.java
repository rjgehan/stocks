package com.stocks.contract.admin.dto;

import java.time.Instant;

/** {@code GET /admin/invites} item (§21.1): {@code {inviteId, uses, maxUses, expiresAt, revokedAt}}. */
public record InviteDto(String inviteId, int uses, int maxUses, Instant expiresAt, Instant revokedAt) {}
