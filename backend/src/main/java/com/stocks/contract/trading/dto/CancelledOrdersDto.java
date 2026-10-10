package com.stocks.contract.trading.dto;

import java.util.List;

/** {@code DELETE /orders?symbol=} and MCP {@code cancel_all_orders} body (§18.2, §20.3): {@code {cancelled: Order[]}}. */
public record CancelledOrdersDto(List<OrderDto> cancelled) {}
