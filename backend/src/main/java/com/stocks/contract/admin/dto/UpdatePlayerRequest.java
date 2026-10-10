package com.stocks.contract.admin.dto;

import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;

/**
 * {@code PATCH /admin/players/{handle}} (§21.1); null = unchanged. {@code status} ACTIVE or DISABLED only (FROZEN
 * goes through freeze/unfreeze); {@code role} ADMIN only for a HUMAN.
 */
public record UpdatePlayerRequest(String displayName, String aiModel, PlayerStatus status, PlayerRole role,
                                  Boolean apiTradingAllowed) {}
