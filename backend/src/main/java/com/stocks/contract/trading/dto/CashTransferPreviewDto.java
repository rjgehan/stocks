package com.stocks.contract.trading.dto;

import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** §21.1 {@code CashTransferPreview} (§21.2 steps 2–5, read-only). */
public record CashTransferPreviewDto(Instant asOf, SessionType session, List<TransferPreviewAccountDto> accounts) {

  /** {@code warnings}: {@code ACCOUNT_EQUITY_TOO_LOW}, {@code MARKS_DEGRADED}. {@code worstMarkBasis}: a {@code MarkBasis} name. */
  public record TransferPreviewAccountDto(String handle, String accountId, BigDecimal cash, BigDecimal withdrawable,
                                          BigDecimal equityBefore, BigDecimal unitValue, DataQuality marksQuality,
                                          String worstMarkBasis, boolean wouldApply, String skipReason,
                                          List<Warning> warnings) {}
}
