package com.stocks.trading.order;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.ExecutionFilter;
import com.stocks.contract.trading.OpenOrderSummary;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.trading.order.mapper.ExecutionDtoMapper;
import com.stocks.trading.order.mapper.OrderDtoMapper;
import com.stocks.trading.store.ExecutionRow;
import com.stocks.trading.store.OrderRow;
import com.stocks.trading.store.OrderStore;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Read-only order queries over committed state (§18.2 {@code /orders}, {@code /account/executions}). */
@Service
public class OrderQueriesImpl implements OrderQueries {

  private final OrderStore orders;
  private final OrderDtoMapper mapper;

  public OrderQueriesImpl(OrderStore orders, OrderDtoMapper mapper) {
    this.orders = orders;
    this.mapper = mapper;
  }

  @Override
  public Page<OrderDto> list(long accountId, OrderFilter filter, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    List<OrderRow> rows = orders.list(accountId, filter, cursor, n + 1);
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.receivedAt(), r.id())).map(mapper::toDto);
  }

  @Override
  public Optional<OrderDto> byPublicId(long accountId, String orderId, boolean withDetail) {
    return orders.byPublicId(accountId, orderId).map(r -> withDetail ? detail(r) : mapper.toDto(r));
  }

  @Override
  public Optional<OrderDto> byClientOrderId(long accountId, String clientOrderId, boolean withDetail) {
    return orders.byClientOrderId(accountId, clientOrderId).map(r -> withDetail ? detail(r) : mapper.toDto(r));
  }

  private OrderDto detail(OrderRow r) {
    List<ExecutionDto> executions = orders.executionsOfOrder(r.id()).stream().map(ExecutionDtoMapper::toDto).toList();
    return mapper.toDto(r, executions, orders.events(r.id()));
  }

  @Override
  public Page<ExecutionDto> executions(long accountId, ExecutionFilter filter, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    List<ExecutionRow> rows = orders.executions(accountId, filter, cursor, n + 1);
    return Page.fromLookahead(rows, n, e -> Cursor.of(e.executedAt(), e.id())).map(ExecutionDtoMapper::toDto);
  }

  @Override
  public List<OpenOrderSummary> openOrdersBySymbol(String symbol) {
    return orders.openBySymbol(NormalizedOrder.normalizeSymbol(symbol)).stream()
        .map(r -> new OpenOrderSummary(r.publicId(), r.accountId(), r.playerId(), r.symbol(), r.side(), r.type(),
            r.status(), r.triggered(), r.extendedHours()))
        .toList();
  }
}
