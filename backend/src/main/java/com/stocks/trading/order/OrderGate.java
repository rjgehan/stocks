package com.stocks.trading.order;

import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.EngineState;
import com.stocks.trading.engine.TradingEngine;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.PlayerRow;
import org.springframework.stereotype.Component;

/**
 * Order checks 1–6 on the request thread (§11.2): engine state, player status, the {@code trade} scope, the
 * {@code clientOrderId} (done by the caller with {@link OrderReplay#requireClientOrderId}), the idempotency fast path
 * (also the caller's), and the write rate limit. <b>The only code that charges the order-write bucket</b> (§17.5), once
 * per write and after the fast path, so a replay is free and a preview never costs a write token.
 */
@Component
public class OrderGate {

  private final TradingEngine engine;
  private final AccountStore accounts;
  private final RateLimitService rateLimits;

  public OrderGate(TradingEngine engine, AccountStore accounts, RateLimitService rateLimits) {
    this.engine = engine;
    this.accounts = accounts;
    this.rateLimits = rateLimits;
  }

  /** Checks 1–3. {@code write}: the {@code trade} scope is required (not for previews). */
  public void preflight(PlayerPrincipal who, boolean write) {
    if (engine.status().state() == EngineState.HALTED) {
      throw ApiException.of(ErrorCode.ENGINE_HALTED, "The trading engine is halted.")
          .withHint("Retry later; an admin must resume the engine.").withRetryAfter(60);
    }
    PlayerStatus status = accounts.player(who.playerId()).map(PlayerRow::status).orElse(PlayerStatus.ACTIVE);
    if (status == PlayerStatus.FROZEN) {
      throw ApiException.of(ErrorCode.PLAYER_FROZEN, "Your player is frozen by the admin.");
    }
    if (status == PlayerStatus.DISABLED) {
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, "Your player is disabled.");
    }
    if (write && !who.hasScope(Scopes.TRADE)) {
      throw ApiException.of(ErrorCode.FORBIDDEN_SCOPE, "This API key lacks the trade scope.")
          .withHint("Ask the admin for a key with the trade scope.");
    }
  }

  /** Check 4: one order-write token. Free until WS1's rate limiter lands (a Day-0 stub). */
  public void chargeWrite(long playerId) {
    try {
      rateLimits.consumeWrite(playerId);
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
    }
  }

  /** A replay found only on the engine thread is free (§11.2): give the token back. */
  public void refundWrite(long playerId) {
    try {
      rateLimits.refundWrite(playerId);
    } catch (ApiException e) {
      if (e.code() != ErrorCode.NOT_IMPLEMENTED) {
        throw e;
      }
    }
  }
}
