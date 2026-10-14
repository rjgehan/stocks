package com.stocks.contract.agents.dto;

import com.stocks.common.domain.PlayerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /me/bots/{handle}/run-requests} (§20.9): the bot's latest requests, newest first, and what the Run now
 * button needs to know before it offers a run: the bot's status (only an ACTIVE bot can be run) and when a trader
 * last asked for the bot's requests. The claude-trader service asks every minute for each agent it serves, so a
 * {@code traderSeenAt} that is null or minutes old means nothing would pick a request up.
 */
public record RunRequestListDto(
    @Schema(description = "The bot's status; only an ACTIVE bot can be run") PlayerStatus botStatus,
    @Schema(description = "When a trader last asked for the bot's run requests (with the bot's own key); null when "
        + "none has since the app started") Instant traderSeenAt,
    List<RunRequestDto> requests) {}
