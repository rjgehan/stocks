package com.stocks.contract.trading.dto;

import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import java.math.BigDecimal;
import java.time.Instant;

/** §21.1 {@code TransferRow} ({@code GET /admin/cash-transfers}). {@code by} is the admin's handle. */
public record TransferRowDto(String transferId, String batchId, String handle, String accountId, TransferType type,
                             BigDecimal amount, boolean isContribution, TransferStatus status, String skipReason,
                             String memo, Instant effectiveAt, String by, BigDecimal equityBefore,
                             BigDecimal unitValue) {}
