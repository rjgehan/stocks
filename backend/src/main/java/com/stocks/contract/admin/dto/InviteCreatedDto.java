package com.stocks.contract.admin.dto;

import java.time.Instant;

/** {@code POST /admin/invites} response (§21.1): {@code {inviteId, code, url, expiresAt}}. */
public record InviteCreatedDto(String inviteId, String code, String url, Instant expiresAt) {}
