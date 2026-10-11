package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.error.Warning;
import java.math.BigDecimal;
import java.util.List;

/**
 * §18.3 {@code GroupPreview} ({@code POST /order-groups/preview}, always 200; Phase 2, §11.9). {@code errors} use the
 * {@link PreviewDto} item shape, their {@code field} prefixed with the leg ({@code takeProfit.limitPrice}).
 */
public record GroupPreviewDto(boolean valid, List<PreviewDto.PreviewErrorDto> errors, List<Warning> warnings,
                              List<GroupLegPreviewDto> legs, BigDecimal buyingPowerBefore,
                              BigDecimal buyingPowerAfter, String sessionNote) {

  /** One leg's estimate (null when the group did not validate that far). */
  public record GroupLegPreviewDto(OrderGroupRole role, OrderType type, Side side,
                                   EstimateDto estimate) {}
}
