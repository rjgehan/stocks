package com.stocks.contract.trading.dto;

import java.math.BigDecimal;

/** {@code ActivityDetail} {@code type: "SPLIT"} (§14). */
public record SplitDetailDto(String corporateActionId, BigDecimal oldRate, BigDecimal newRate, BigDecimal qtyBefore,
                             BigDecimal qtyAfter, BigDecimal costBasis, BigDecimal cashInLieu, BigDecimal dustQty,
                             BigDecimal realizedPnl) implements ActivityDetailDto {}
