package com.stocks.contract.manualbot.dto;

import com.stocks.common.domain.PlayerStatus;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.time.Instant;

/**
 * §20.8 console state of one AI bot, for its owner or the admin ({@code GET /me/bots/{handle}/manual}).
 * {@code instructions} is {@code ""} until the first save. {@code activeApiKeys} and {@code activeConnectors}
 * (unrevoked, used OAuth grants acting as the bot) drive the "also trades with a key or connector" banner.
 */
public record ManualBotDto(PlayerSummaryDto bot, String ownerHandle, PlayerStatus status, String instructions,
                           Instant instructionsUpdatedAt, String instructionsUpdatedBy, int activeApiKeys,
                           int activeConnectors, int sessionsToday, int sessionsPerDay, ManualLimitsDto limits) {}
