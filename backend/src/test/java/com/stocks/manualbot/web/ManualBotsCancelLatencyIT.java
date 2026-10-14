package com.stocks.manualbot.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.trading.dto.OrderDto;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * §20.8 with cancel latency on (§11.10, off by default): a CANCEL row of a working order reports
 * {@code CANCEL_REQUESTED} with the order {@code PENDING_CANCEL}; the sweep cancels it afterwards.
 */
@TestPropertySource(properties = "stocks.rules.cancel-latency.enabled=true")
class ManualBotsCancelLatencyIT extends ManualBotsTestBase {

  @Test
  void aCancelUnderLatencyIsRequested() throws Exception {
    OrderDto bid = orders.place(botP, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.GTC, false), meta())
        .order();
    after(Duration.ofSeconds(1));
    String sessionId = newPrompt(bot);
    assertThat(reply(bot, sessionId, block(sessionId, "Pulled a stale bid.", cancel(bid.orderId()))).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse done = execute(ownerP, bot, sessionId, 1);
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat((String) json(done, "$.actions[0].result.status")).isEqualTo("CANCEL_REQUESTED");
    assertThat((String) json(done, "$.actions[0].result.orderStatus")).isEqualTo("PENDING_CANCEL");
    assertThat((String) json(done, "$.actions[0].result.orderId")).isEqualTo(bid.orderId());
    after(Duration.ofSeconds(1));
    assertThat(orderQueries.byPublicId(botAccount, bid.orderId(), false).orElseThrow().status())
        .isEqualTo(OrderStatus.CANCELLED);
    // a CANCEL_REQUESTED row is done: executing again changes nothing
    MockHttpServletResponse again = execute(ownerP, bot, sessionId, 1);
    assertThat((String) json(again, "$.actions[0].result.status")).isEqualTo("CANCEL_REQUESTED");
  }
}
