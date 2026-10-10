package com.stocks.contract.trading.dto;

import java.math.BigDecimal;

/** {@code ActivityDetail} {@code type: "NAME_CHANGE"} (§14). */
public record NameChangeDetailDto(String corporateActionId, String oldSymbol, String newSymbol, BigDecimal qty)
    implements ActivityDetailDto {}
