package com.stocks.contract.trading.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §21.1 {@code TransferBatchResult} ({@code POST /admin/cash-transfers}). */
public record TransferBatchResultDto(String batchId, TransferScope scope, TransferType type, BigDecimal amount,
                                     String memo, Instant createdAt, int applied, int skipped,
                                     List<TransferResultDto> results) {

  public record TransferResultDto(String transferId, String handle, String displayName, String accountId,
                                  TransferStatus status, String skipReason, BigDecimal amount,
                                  BigDecimal equityBefore, BigDecimal unitValue, BigDecimal cashAfter,
                                  DataQuality marksQuality, boolean rebased, List<Warning> warnings) {}
}
