package com.stocks.contract.trading.dto;

import java.math.BigDecimal;

/** {@code ActivityDetail} {@code type: "RESET"} (§10.5): written on both runs, CLOSED and OPENED. */
public record ResetDetailDto(ResetSide side, String fromAccountId, String toAccountId, int runNo,
                             BigDecimal finalEquity, BigDecimal startingCash, String memo) implements ActivityDetailDto {

  public enum ResetSide { CLOSED, OPENED }
}
