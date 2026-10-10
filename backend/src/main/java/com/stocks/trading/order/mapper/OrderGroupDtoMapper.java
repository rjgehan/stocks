package com.stocks.trading.order.mapper;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupStatus;
import com.stocks.common.domain.OrderStatus;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.trading.order.OrderValidator;
import com.stocks.trading.store.OrderGroupRow;
import com.stocks.trading.store.OrderRow;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@code order_group} + its legs → {@link OrderGroupDto} (§18.3, §11.9): the current legs (REPLACED ones left out) in
 * role order, in the list shape, and the status derived from them: ACTIVE while any leg is open; then COMPLETED if any
 * leg executed, else the entry's (or the OCO's first leg's) terminal status, CANCELLED or EXPIRED.
 */
@Component
public class OrderGroupDtoMapper {

  private final OrderDtoMapper orders;

  public OrderGroupDtoMapper(OrderDtoMapper orders) {
    this.orders = orders;
  }

  /** {@code legs}: every leg of the group, REPLACED ones included. */
  public OrderGroupDto toDto(OrderGroupRow g, List<OrderRow> legs) {
    List<OrderRow> current = current(legs);
    List<OrderDto> dtos = current.stream().map(orders::toDto).toList();
    return new OrderGroupDto(g.publicId(), g.clientOrderId(), g.type(), g.symbol(), OrderValidator.qtyOut(g.qty()),
        g.timeInForce(), status(legs), g.createdAt(), g.updatedAt(), dtos);
  }

  /** The current legs, ENTRY, TAKE_PROFIT, STOP_LOSS, SECONDARY. */
  public static List<OrderRow> current(List<OrderRow> legs) {
    return legs.stream().filter(r -> r.status() != OrderStatus.REPLACED)
        .sorted(Comparator.comparing(OrderRow::groupRole, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(OrderRow::id))
        .toList();
  }

  public static OrderGroupStatus status(List<OrderRow> legs) {
    List<OrderRow> current = current(legs);
    if (current.stream().anyMatch(OrderRow::isOpen)) {
      return OrderGroupStatus.ACTIVE;
    }
    if (legs.stream().anyMatch(r -> r.filledQty().signum() > 0)) {
      return OrderGroupStatus.COMPLETED;
    }
    OrderRow first = current.stream().filter(r -> r.groupRole() == OrderGroupRole.ENTRY).findFirst()
        .orElse(current.isEmpty() ? null : current.get(0));
    return first != null && first.status() == OrderStatus.EXPIRED ? OrderGroupStatus.EXPIRED
        : OrderGroupStatus.CANCELLED;
  }
}
