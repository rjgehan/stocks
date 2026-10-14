package com.stocks.contract.agents.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /me/bots/run-requests} (§20.9 "Run all"): one item per bot the caller may run (the bots they own, or
 * every listed AI player for an ACTIVE admin), by handle, and the server's clock when it answered.
 */
public record RunAllResultDto(
    boolean dryRun,
    @Schema(description = "The server's clock when it answered") Instant asOf,
    @Schema(description = "How many bots got a new request") int requested,
    List<RunAllItemDto> results) {}
