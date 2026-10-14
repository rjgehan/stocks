package com.stocks.contract.agents.dto;

import java.time.Instant;

/**
 * §20.9 {@code RunRequest}: a "Run now" request for an AI player. {@code handle} is the bot, {@code requestedBy} the
 * human who asked (its owner or the admin). {@code claimedAt} is when the trader picked it up, {@code finishedAt} when
 * it reported back or the request expired; {@code message} is the trader's report or why the request expired.
 */
public record RunRequestDto(String id, String handle, String requestedBy, Instant requestedAt, boolean dryRun,
                            RunRequestStatus status, Instant claimedAt, Instant finishedAt, String message) {}
