package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One bot of a "Run all" (§20.9): what happened, why when it was skipped, a sentence for the owner, and the request
 * (the new one, or the one already open; null when skipped).
 */
public record RunAllItemDto(
    String handle,
    RunAllOutcome outcome,
    @Schema(description = "Set when outcome is SKIPPED") RunAllSkipReason reason,
    @Schema(description = "A sentence for the owner: why the bot was skipped, or which request is already open")
    String message,
    @Schema(description = "The new request (REQUESTED) or the open one (ALREADY_OPEN); null when SKIPPED")
    RunRequestDto request) {}
