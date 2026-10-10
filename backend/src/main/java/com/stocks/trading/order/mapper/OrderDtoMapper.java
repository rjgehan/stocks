package com.stocks.trading.order.mapper;

import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderEventDto;
import com.stocks.contract.trading.dto.OrderGroupRefDto;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.store.OrderEventRow;
import com.stocks.trading.store.OrderRow;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code trade_order} row → §18.3 {@code Order}. Quantities are written without trailing zeros ({@code "0.95274"},
 * {@code "0"}, §18.4), prices at scale 4, cash at scale 2, the average fill price at scale 6.
 */
@Component
public class OrderDtoMapper {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JsonMapper json;

  public OrderDtoMapper(JsonMapper json) {
    this.json = json;
  }

  /** The list shape: {@code executions} and {@code events} null. */
  public OrderDto toDto(OrderRow r) {
    return toDto(r, null, null);
  }

  public OrderDto toDto(OrderRow r, List<ExecutionDto> executions, List<OrderEventRow> events) {
    List<OrderEventDto> ev = events == null ? null : events.stream().map(this::event).toList();
    return new OrderDto(r.publicId(), r.clientOrderId(), r.accountPublicId(), r.symbol(), r.side(), r.type(),
        OrderValidator.qtyOut(r.qty()), r.notional(), r.fractional(), r.limitPrice(), r.stopPrice(), r.trailAmount(),
        r.trailPercent(), r.watermarkPrice(), r.collarPrice(), r.timeInForce(), r.extendedHours(), r.apiStatus(),
        r.waitReason(), r.statusReason(), r.triggered(), OrderValidator.qtyOut(r.filledQty()), r.filledPrincipal(),
        r.avgFillPrice(), r.feesTotal(), r.reservedCash(), OrderValidator.qtyOut(r.reservedQty()), r.sessionDate(),
        r.receivedAt(), r.acceptedAt(), r.eligibleAt(), r.activatedAt(), r.triggeredAt(), r.expiresAt(), r.doneAt(),
        r.replacesPublicId(), r.replacedByPublicId(), r.channel(), r.rationale(), executions, ev, group(r),
        r.doNotReduce(), r.cancelRequestedAt());
  }

  /** §11.9: the leg's group, or null for a single order. */
  static OrderGroupRefDto group(OrderRow r) {
    if (!r.inGroup()) {
      return null;
    }
    return new OrderGroupRefDto(r.groupPublicId(), r.groupType(), r.groupRole(), r.parentPublicId(),
        r.held() && r.isOpen());
  }

  OrderEventDto event(OrderEventRow e) {
    Map<String, Object> detail = e.detail() == null ? null : json.readValue(e.detail(), MAP);
    return new OrderEventDto(e.seq(), e.type(), e.statusAfter(), e.occurredAt(), detail);
  }
}
