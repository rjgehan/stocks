package com.stocks.contract.trading.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** {@code ActivityDetail} {@code type: "GFV"} (§10.4). */
public record GfvDetailDto(String violationId, String orderId, BigDecimal unsettledAmount,
                           LocalDate fundingSettleDate, int activeCount) implements ActivityDetailDto {}
