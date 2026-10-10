package com.stocks.contract.identity.dto;

import com.stocks.common.domain.PlayerKind;
import java.time.Instant;

/** §18.3 {@code PlayerSummary}: the public face of a player (league, feed, profiles). */
public record PlayerSummaryDto(String playerId, String handle, String displayName, PlayerKind kind, String aiModel,
                               String avatarSeed, Instant memberSince, int resets) {}
