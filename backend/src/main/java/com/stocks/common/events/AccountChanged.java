package com.stocks.common.events;

import java.time.Instant;

/** Balances, positions or restrictions of an account changed (§5.3). {@code reason}: e.g. {@code FILL}, {@code RESET}. */
public record AccountChanged(long accountId, long playerId, String reason, Instant at) {}
