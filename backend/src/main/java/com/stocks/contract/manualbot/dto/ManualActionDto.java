package com.stocks.contract.manualbot.dto;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.EstimateDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * One row of a pasted reply (§20.8), 1-based {@code index} in reply order, with the {@code line} of its JSON object.
 * {@code executable}: no row ERROR. {@code estimate} comes from the live preview; {@code result} is set once the row
 * was placed. {@code orderId} is the order a CANCEL targets.
 */
public record ManualActionDto(int index, Integer line, ManualActionKind action, String symbol, OrderType type,
                              BigDecimal qty, BigDecimal notional, BigDecimal limitPrice, BigDecimal stopPrice,
                              BigDecimal trailAmount, BigDecimal trailPercent, TimeInForce timeInForce,
                              boolean extendedHours, List<String> acknowledge, String orderId, String rationale,
                              boolean executable, List<ManualIssueDto> issues, EstimateDto estimate,
                              ManualActionResultDto result) {}
