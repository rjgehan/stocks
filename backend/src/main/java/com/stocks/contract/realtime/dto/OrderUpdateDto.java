package com.stocks.contract.realtime.dto;

import com.stocks.common.domain.OrderEventType;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;

/** §19 {@code /user/queue/orders} payload {@code {event, order, execution|null}}, after commit. */
public record OrderUpdateDto(OrderEventType event, OrderDto order, ExecutionDto execution) {}
