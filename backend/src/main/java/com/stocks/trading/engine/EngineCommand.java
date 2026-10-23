package com.stocks.trading.engine;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.TransitionType;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import com.stocks.trading.order.NormalizedGroup;
import com.stocks.trading.order.NormalizedOrder;
import com.stocks.trading.order.OrderOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The engine commands (§12.1, CONTRACT). Each runs on {@code engine-main} in exactly one JDBC transaction; events are
 * published only after commit (§4). Services build them; nothing else reaches the engine.
 *
 * <p>The corporate-action commands (§14) delegate to {@code trading.corpactions.CorporateActionApplier}.
 */
public sealed interface EngineCommand {

  /** Commands a halted engine still accepts (§12.9). */
  default boolean allowedWhileHalted() {
    return false;
  }

  /** §11.2 checks 6–27 (authoritative) and acceptance; result: {@code PlaceOrderResponse}. */
  record PlaceOrderCmd(long accountId, OrderOrigin origin, NormalizedOrder order, String requestHash,
                       Instant receivedAt) implements EngineCommand {}

  /** §11.6 cancel-replace; result: {@code PlaceOrderResponse} of the new order. */
  record ReplaceOrderCmd(long accountId, OrderOrigin origin, String orderId, ReplaceOrderRequest request,
                         Instant receivedAt) implements EngineCommand {}

  /** {@code accountIdOrNull} null = any account (admin, §21.1); result: {@code OrderDto}. */
  record CancelOrderCmd(Long accountIdOrNull, String orderId, CancelReason reason, long actorPlayerId)
      implements EngineCommand {}

  /** Result: the cancelled {@code OrderDto}s. */
  record CancelAllCmd(long accountId, String symbolOrNull, CancelReason reason, long actorPlayerId)
      implements EngineCommand {}

  /** §11.9 a group order, checks 1–27 for every leg (authoritative); result: {@code PlaceOrderGroupResponse}. */
  record PlaceOrderGroupCmd(long accountId, OrderOrigin origin, NormalizedGroup group, String requestHash,
                            Instant receivedAt) implements EngineCommand {}

  /** §11.9 cancel-replace of a group's changed legs; result: {@code PlaceOrderGroupResponse}. */
  record ReplaceOrderGroupCmd(long accountId, OrderOrigin origin, String groupId, ReplaceOrderGroupRequest request,
                              Instant receivedAt) implements EngineCommand {}

  /** §11.9 cancels every open leg of a group (user cancel, §11.10 latency applies); result: {@code OrderGroupDto}. */
  record CancelOrderGroupCmd(long accountId, String groupId, long actorPlayerId) implements EngineCommand {}

  /** An explicit sweep (activation, expiry, re-evaluation, §12.2); the loop also sweeps on its own. */
  record SweepCmd() implements EngineCommand {}

  /** §9.4 session transition: pauses, resumes and expiries; never fills for time the app was down. */
  record TransitionCmd(LocalDate tradingDate, TransitionType type) implements EngineCommand {}

  /** §21.2; result: {@code TransferBatchResultDto}. */
  record ApplyCashTransfersCmd(CashTransferCommand transfer, IdempotencyRef idem, long adminPlayerId)
      implements EngineCommand {}

  /** Run 1 with its INITIAL transfer (§10.1); idempotent per player. Result: the account id. */
  record OpenAccountCmd(long playerId, BigDecimal startingCash, long actorPlayerId) implements EngineCommand {}

  /** §10.5 reset; result: the new account id. */
  record ResetAccountCmd(long playerId, BigDecimal startingCashOrNull, String memo, IdempotencyRef idem,
                         long actorPlayerId) implements EngineCommand {}

  /** §10.5 freeze/disable step 2. */
  record RestrictAccountCmd(long playerId, AccountRestriction restriction, CancelReason cancelReason, String reason,
                            long actorPlayerId) implements EngineCommand {}

  /** §10.5 unfreeze/enable step 2: the restriction becomes NONE or the recomputed GFV restriction. */
  record LiftRestrictionCmd(long playerId, long actorPlayerId) implements EngineCommand {}

  /**
   * §21.3 break-glass view: an {@code ADMIN_VIEW} {@code account_event}. No caller since public mode; kept for the
   * historical ADMIN_VIEW rows.
   */
  record RecordAdminViewCmd(long accountId, String reason, long actorPlayerId) implements EngineCommand {}

  /** §25 03:30 ET: lift GFV restrictions whose time has passed. */
  record DailyMaintenanceCmd(LocalDate date) implements EngineCommand {}

  /** §14 03:45 ET on the ex-date: applies one PENDING corporate action (by public id); result: the action row. */
  record ApplyCorporateActionCmd(String actionId) implements EngineCommand {}

  /** §14, §21.1 the admin's APPLY (splits, confirmed rates, as of now) or SKIP; result: the action row. */
  record ResolveCorporateActionCmd(String actionId, CorporateActionResolution resolution, BigDecimal oldRate,
                                   BigDecimal newRate, String note, long actorPlayerId) implements EngineCommand {}

  /** §14 06:00 ET: pays ACCRUED dividends with {@code payable_date ≤ date}; result: how many were paid. */
  record PayDividendsCmd(LocalDate date) implements EngineCommand {}

  /**
   * §30 after 16:00 ET on the contract's expiration date: cash-settles every long position in {@code symbol} at its
   * intrinsic value from {@code underlyingClose} (the underlying's official close that day) and cancels its open
   * orders; result: {@code OptionExpiryHandler.Result}. Idempotent per account.
   */
  record SettleOptionExpiryCmd(String symbol, BigDecimal underlyingClose) implements EngineCommand {}

  /** §8.4 I1–I9; result: {@code InvariantResult}. A failure halts the engine. */
  record CheckInvariantsCmd() implements EngineCommand {
    @Override
    public boolean allowedWhileHalted() {
      return true;
    }
  }

  record HaltEngineCmd(String reason, long actorPlayerId) implements EngineCommand {
    @Override
    public boolean allowedWhileHalted() {
      return true;
    }
  }

  /** Runs the invariants first and resumes only if they hold. */
  record ResumeEngineCmd(long actorPlayerId) implements EngineCommand {
    @Override
    public boolean allowedWhileHalted() {
      return true;
    }
  }
}
