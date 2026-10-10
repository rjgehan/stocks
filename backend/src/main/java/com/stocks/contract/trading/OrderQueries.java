package com.stocks.contract.trading;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import java.util.List;
import java.util.Optional;

/** Read-only order queries (WS3). {@code withDetail} fills {@code executions} and {@code events}. */
public interface OrderQueries {

  /** Newest first; list items have {@code executions} and {@code events} null. */
  Page<OrderDto> list(long accountId, OrderFilter filter, Cursor cursor, int limit);

  Optional<OrderDto> byPublicId(long accountId, String orderId, boolean withDetail);

  Optional<OrderDto> byClientOrderId(long accountId, String clientOrderId, boolean withDetail);

  Page<ExecutionDto> executions(long accountId, ExecutionFilter filter, Cursor cursor, int limit);

  /** {@code GET /order-groups/{groupId}} (Phase 2, §11.9): the group with its current legs (list shape). */
  Optional<OrderGroupDto> group(long accountId, String groupId);

  /** For leases and the UI. */
  List<OpenOrderSummary> openOrdersBySymbol(String symbol);
}
