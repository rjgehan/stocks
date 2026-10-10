package com.stocks.trading.order;

import static com.stocks.common.domain.OrderStatus.CANCELLED;
import static com.stocks.common.domain.OrderStatus.EXPIRED;
import static com.stocks.common.domain.OrderStatus.FILLED;
import static com.stocks.common.domain.OrderStatus.PARTIALLY_FILLED;
import static com.stocks.common.domain.OrderStatus.PENDING;
import static com.stocks.common.domain.OrderStatus.REJECTED;
import static com.stocks.common.domain.OrderStatus.REPLACED;
import static com.stocks.common.domain.OrderStatus.WORKING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.OrderStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** §11.5, exhaustively: every legal transition passes, every other one throws. */
class OrderStateMachineTest {

  static final Map<OrderStatus, Set<OrderStatus>> LEGAL = Map.of(
      PENDING, EnumSet.of(WORKING, CANCELLED, EXPIRED, REPLACED),
      WORKING, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REPLACED),
      PARTIALLY_FILLED, EnumSet.of(PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED));

  @Test
  void everyTransitionIsLegalExactlyWhenTheTableSaysSo() {
    for (OrderStatus from : OrderStatus.values()) {
      for (OrderStatus to : OrderStatus.values()) {
        boolean legal = LEGAL.getOrDefault(from, Set.of()).contains(to);
        assertThat(OrderStateMachine.canTransition(from, to)).as(from + " -> " + to).isEqualTo(legal);
        if (legal) {
          assertThat(OrderStateMachine.transition(from, to)).isEqualTo(to);
        } else {
          assertThatThrownBy(() -> OrderStateMachine.transition(from, to))
              .isInstanceOf(IllegalStateException.class).hasMessageContaining(from + " -> " + to);
        }
      }
    }
  }

  @Test
  void openAndTerminal() {
    assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStateMachine::isOpen))
        .containsExactlyInAnyOrder(PENDING, WORKING, PARTIALLY_FILLED);
    assertThat(OrderStateMachine.isTerminal(FILLED)).isTrue();
    assertThat(OrderStateMachine.isTerminal(REJECTED)).isTrue();
    assertThat(OrderStateMachine.isTerminal(WORKING)).isFalse();
  }
}
