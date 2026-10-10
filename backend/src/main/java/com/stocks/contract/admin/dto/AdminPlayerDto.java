package com.stocks.contract.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §21.1 {@code AdminPlayer = PlayerSummary & {...}}, flattened (the first eight components are
 * {@code PlayerSummary}'s). {@code ret} is the JSON {@code return} (a fraction).
 */
public record AdminPlayerDto(String playerId, String handle, String displayName, PlayerKind kind, String aiModel,
                             String avatarSeed, Instant memberSince, int resets,
                             PlayerRole role, PlayerStatus status, String ownerHandle, String accountId, int runNo,
                             BigDecimal equity, BigDecimal cash, BigDecimal netContributions, BigDecimal pnl,
                             @JsonProperty("return") BigDecimal ret, int openOrders, int activeKeys,
                             AccountRestriction restriction, Instant lastSeenAt, Instant createdAt) {}
