package com.stocks.contract.security;

import java.time.Instant;

/** Remaining per-player budget (§17.5): {@code whoami.rateLimits} and the {@code X-RateLimit-*} headers. */
public record RateLimitStatus(int readsRemaining, int writesRemainingMinute, int writesRemainingDay, Instant resetAt) {}
