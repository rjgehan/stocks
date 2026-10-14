package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /me/bots/{handle}/run-requests} (§20.9): run the bot's check-in now; {@code dryRun} blocks orders. */
public record RunNowRequest(
    @Schema(description = "true: the order tools are blocked and the bot only says which orders it would place "
        + "(default false)") Boolean dryRun) {}
