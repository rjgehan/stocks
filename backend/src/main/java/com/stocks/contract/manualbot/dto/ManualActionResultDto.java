package com.stocks.contract.manualbot.dto;

import com.stocks.common.domain.OrderStatus;
import java.time.Instant;

/** §20.8: what happened when a row was placed; {@code code} and {@code message} explain a FAILED row. */
public record ManualActionResultDto(ManualResultStatus status, String orderId, OrderStatus orderStatus, String code,
                                    String message, Instant at) {}
