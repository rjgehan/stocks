package com.stocks.trading.store;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.money.Qty;
import com.stocks.trading.order.OrderStateMachine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One {@code trade_order} row (§8.1), with the public ids of its account and replace links. Phase 2: its group
 * ({@code groupId}, {@code groupPublicId}, {@code groupType}, {@code groupRole}; null for a single order), a child's
 * entry ({@code parentOrderId}), {@code held} (§11.9), {@code doNotReduce} (§14) and the cancel-latency instants
 * (§11.10).
 */
public record OrderRow(long id, String publicId, long accountId, String accountPublicId, long playerId,
                       String clientOrderId, String requestHash, String symbol, Side side, OrderType type,
                       BigDecimal qty, BigDecimal notional, boolean fractional, BigDecimal limitPrice,
                       BigDecimal stopPrice, BigDecimal trailAmount, BigDecimal trailPercent,
                       BigDecimal watermarkPrice, BigDecimal collarPrice, TimeInForce timeInForce,
                       boolean extendedHours, OrderStatus status, WaitReason waitReason, String statusReason,
                       boolean triggered, boolean hasRested, BigDecimal filledQty, BigDecimal filledPrincipal,
                       BigDecimal avgFillPrice, BigDecimal feesTotal, BigDecimal reservedCash, BigDecimal reservedQty,
                       LocalDate sessionDate, Instant receivedAt, Instant acceptedAt, Instant eligibleAt,
                       Instant activatedAt, Instant triggeredAt, Instant lastFillAt, Instant expiresAt,
                       Instant doneAt, String replacesPublicId, String replacedByPublicId, Channel channel,
                       Long apiKeyId, String rationale, boolean rationalePublic, Long groupId,
                       String groupPublicId, OrderGroupType groupType, OrderGroupRole groupRole, Long parentOrderId,
                       String parentPublicId, boolean held, boolean doNotReduce, Instant cancelRequestedAt,
                       Instant cancelEffectiveAt) {

  public boolean isOpen() {
    return OrderStateMachine.isOpen(status);
  }

  public boolean isBuy() {
    return side == Side.BUY;
  }

  /** A leg of an order group (§11.9). */
  public boolean inGroup() {
    return groupId != null;
  }

  /** A user cancel is waiting out the cancel latency (§11.10); the API shows {@code PENDING_CANCEL}. */
  public boolean cancelPending() {
    return cancelRequestedAt != null && isOpen();
  }

  /** The status the API shows: {@code PENDING_CANCEL} while a requested cancel is pending (§11.10). */
  public OrderStatus apiStatus() {
    return cancelPending() ? OrderStatus.PENDING_CANCEL : status;
  }

  /**
   * The quantity still to fill, or null for a notional order that has not filled (its quantity is decided at
   * execution, §11.1).
   */
  public BigDecimal remainingQty() {
    if (qty == null) {
      return null;
    }
    return qty.subtract(filledQty).max(Qty.ZERO);
  }
}
