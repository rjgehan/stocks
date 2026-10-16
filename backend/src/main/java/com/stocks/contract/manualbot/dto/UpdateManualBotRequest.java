package com.stocks.contract.manualbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code PUT /me/bots/{handle}/manual} (§20.8): null = unchanged; instructions {@code ""} clears them. */
public record UpdateManualBotRequest(
    @Schema(description = "0-4000 characters; public (part of every prompt). Key-like strings are removed.")
    String instructions,
    @Schema(description = "The bot's model label, 1-24 characters (e.g. Gemini 3)") String aiModel,
    @Schema(description = "The bot's display name, 2-40 characters: letters, digits, space, _ . -")
    String displayName) {}
