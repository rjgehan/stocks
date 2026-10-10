package com.stocks.contract.trading.dto;

import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.util.List;

/** §18.3 {@code Preview} ({@code POST /orders/preview}, always 200; {@code valid=false} lists {@code errors}). */
public record PreviewDto(boolean valid, List<PreviewErrorDto> errors, List<Warning> warnings, EstimateDto estimate,
                         BigDecimal buyingPowerBefore, BigDecimal buyingPowerAfter, String sessionNote) {

  /** One failed check: {@code code} is an {@code ErrorCode} name. */
  public record PreviewErrorDto(String code, String field, String message, String hint) {}
}
