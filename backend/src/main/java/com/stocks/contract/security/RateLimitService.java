package com.stocks.contract.security;

/** Per-player Bucket4j budgets shared across all of a player's keys and channels (§17.5, WS1). */
public interface RateLimitService {

  /** {@code whoami.rateLimits} and the {@code X-RateLimit-*} headers. */
  RateLimitStatus remaining(long playerId);

  /**
   * Charges one order-write token. Called only by {@code OrderGate} (§11.2 check 4).
   *
   * @throws com.stocks.common.error.ApiException {@code RATE_LIMITED} with {@code retryAfterS}
   */
  void consumeWrite(long playerId);

  /** Refunds a write token: a replay detected only on the engine thread is free (§11.2). */
  void refundWrite(long playerId);
}
