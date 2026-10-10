package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One {@code corporate_action} row (§8.1, §14). Rates are {@code NUMERIC(19,8)}; unused columns are null. */
public record CorporateActionRow(long id, String publicId, String providerRef, String symbol, CorporateActionType type,
                                 LocalDate exDate, LocalDate recordDate, LocalDate payableDate, BigDecimal oldRate,
                                 BigDecimal newRate, BigDecimal cashRate, String newSymbol,
                                 CorporateActionStatus status, CorporateActionOrigin origin, String detail,
                                 Instant createdAt, Instant appliedAt, Long resolvedBy) {

  public boolean isSplit() {
    return type == CorporateActionType.FORWARD_SPLIT || type == CorporateActionType.REVERSE_SPLIT;
  }

  /** A {@code new-for-old} label for messages: {@code 2-for-1} (forward), {@code 1-for-8} (reverse). */
  public String ratio() {
    return plain(newRate) + "-for-" + plain(oldRate);
  }

  static String plain(BigDecimal v) {
    return v == null ? "?" : v.stripTrailingZeros().toPlainString();
  }
}
