package com.stocks.contract.trading.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /account/positions/{symbol}/close} (§18.2, §11.8); validated like {@code POST /orders}. */
public record ClosePositionRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientOrderId,
                                   String rationale) {}
