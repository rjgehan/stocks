package com.stocks.contract.trading.dto;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.TransferType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * §21.1 {@code CashTransferRequest} ({@code POST /admin/cash-transfers[/preview]}). {@code type} DEPOSIT, WITHDRAWAL
 * or ADJUSTMENT (never INITIAL). {@code amount}: positive for DEPOSIT/WITHDRAWAL, signed and ≠ 0 for ADJUSTMENT.
 * {@code treatAs}: ADJUSTMENT only, default CONTRIBUTION. {@code memo} 1..200. Defaults false for the booleans.
 */
public record CashTransferRequest(@NotNull @Valid TransferTargetRequest target, @NotNull TransferType type,
                                  @NotNull BigDecimal amount, AdjustmentTreatment treatAs, @NotBlank String memo,
                                  Boolean allOrNothing, Boolean acceptDegradedMarks) {}
