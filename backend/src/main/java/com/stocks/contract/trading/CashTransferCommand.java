package com.stocks.contract.trading;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferType;
import java.math.BigDecimal;
import java.util.Set;

/**
 * An admin cash transfer, normalized from {@code CashTransferRequest} (§21.2). {@code handleOrNull} for SINGLE;
 * {@code kinds} for ALL (both kinds when the request omitted them). {@code amount}: positive for DEPOSIT and
 * WITHDRAWAL, signed and ≠ 0 for ADJUSTMENT.
 */
public record CashTransferCommand(TransferScope scope, String handleOrNull, Set<PlayerKind> kinds, TransferType type,
    BigDecimal amount, AdjustmentTreatment treatAs, String memo, boolean allOrNothing, boolean acceptDegradedMarks) {}
