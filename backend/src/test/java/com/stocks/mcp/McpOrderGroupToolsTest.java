package com.stocks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupStatus;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.OrderGroupRefDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.realtime.TradingSamples;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/** The Phase 2 group-order tools (§20.3 #39–#42) and {@code do_not_reduce} on the order tools, on test doubles. */
class McpOrderGroupToolsTest {

  McpToolHarness h;

  @AfterEach
  void closeHarness() {
    h.close();
  }

  @BeforeEach
  void setUp() {
    h = new McpToolHarness();
    when(h.accounts.activeAccountId(anyLong())).thenReturn(1L);
  }

  static OrderGroupDto group() {
    OrderDto entry = TradingSamples.order("ord_1", "AAPL", Side.BUY, OrderType.LIMIT, OrderStatus.PENDING, "10", "0",
        null, null, null, null);
    OrderDto leg = new OrderDto(entry.orderId(), entry.clientOrderId(), entry.accountId(), entry.symbol(),
        entry.side(), entry.type(), entry.qty(), entry.notional(), entry.fractional(), entry.limitPrice(),
        entry.stopPrice(), entry.trailAmount(), entry.trailPercent(), entry.watermarkPrice(), entry.collarPrice(),
        entry.timeInForce(), entry.extendedHours(), entry.status(), entry.waitReason(), entry.statusReason(),
        entry.triggered(), entry.filledQty(), entry.filledPrincipal(), entry.avgFillPrice(), entry.fees(),
        entry.reservedCash(), entry.reservedQty(), entry.sessionDate(), entry.receivedAt(), entry.acceptedAt(),
        entry.eligibleAt(), entry.activatedAt(), entry.triggeredAt(), entry.expiresAt(), entry.doneAt(),
        entry.replacesOrderId(), entry.replacedByOrderId(), entry.channel(), entry.rationale(), null, null,
        new OrderGroupRefDto("grp_1", OrderGroupType.BRACKET, OrderGroupRole.ENTRY, null, false), false, null);
    return new OrderGroupDto("grp_1", "claude-g-1", OrderGroupType.BRACKET, "AAPL", new BigDecimal("10"),
        TimeInForce.DAY, OrderGroupStatus.ACTIVE, TestTimes.START, TestTimes.START, List.of(leg));
  }

  @Test
  void placeOrderGroupMapsTheFlatLegInputsAndPassesTheMcpMeta() {
    when(h.orderService.placeGroup(any(), any(), any())).thenReturn(new PlaceOrderGroupResponse(group(), null,
        new BigDecimal("500.00"), new BigDecimal("300.00"), List.of(), false));
    JsonNode out = McpToolHarness.ok(h.call("place_order_group", Map.ofEntries(
        Map.entry("client_order_id", "claude-g-1"), Map.entry("group_type", "BRACKET"), Map.entry("symbol", "aapl"),
        Map.entry("qty", "10"), Map.entry("entry_type", "LIMIT"), Map.entry("entry_limit_price", "20.00"),
        Map.entry("take_profit_limit_price", "22.00"), Map.entry("stop_loss_stop_price", "19.00"),
        Map.entry("stop_loss_limit_price", "18.90"), Map.entry("time_in_force", "GTC"),
        Map.entry("rationale", " Breakout with a defined risk. "), Map.entry("do_not_reduce", true))));
    assertThat(out.get("group").get("groupId").asString()).isEqualTo("grp_1");
    assertThat(out.get("group").get("legs").get(0).get("group").get("role").asString()).isEqualTo("ENTRY");
    ArgumentCaptor<PlaceOrderGroupRequest> req = ArgumentCaptor.forClass(PlaceOrderGroupRequest.class);
    ArgumentCaptor<RequestMeta> meta = ArgumentCaptor.forClass(RequestMeta.class);
    verify(h.orderService).placeGroup(eq(h.bot()), req.capture(), meta.capture());
    PlaceOrderGroupRequest r = req.getValue();
    assertThat(r.type()).isEqualTo(OrderGroupType.BRACKET);
    assertThat(r.symbol()).isEqualTo("AAPL");
    assertThat(r.qty()).isEqualByComparingTo("10");
    assertThat(r.timeInForce()).isEqualTo(TimeInForce.GTC);
    assertThat(r.entry().type()).isEqualTo(OrderType.LIMIT);
    assertThat(r.entry().limitPrice()).isEqualByComparingTo("20.00");
    assertThat(r.takeProfit().limitPrice()).isEqualByComparingTo("22.00");
    assertThat(r.stopLoss().stopPrice()).isEqualByComparingTo("19.00");
    assertThat(r.stopLoss().limitPrice()).isEqualByComparingTo("18.90");
    assertThat(r.secondary()).isNull();
    assertThat(r.rationale()).isEqualTo("Breakout with a defined risk.");
    assertThat(r.doNotReduce()).isTrue();
    assertThat(meta.getValue().channel()).isEqualTo(Channel.MCP_KEY);
    assertThat(meta.getValue().mcpTool()).isEqualTo("place_order_group");
  }

  @Test
  void groupWritesNeedTheTradeScopeAndPreviewAndGetNeedAccountRead() {
    assertThat(McpToolHarness.error(h.call("place_order_group", h.keyWith(Scopes.ACCOUNT_READ), Map.of(
        "client_order_id", "c", "group_type", "OCO", "symbol", "AAPL", "qty", "1"))).get("code").asString())
        .isEqualTo("FORBIDDEN_SCOPE");
    assertThat(McpToolHarness.error(h.call("cancel_order_group", h.keyWith(Scopes.ACCOUNT_READ),
        Map.of("group_id", "grp_1"))).get("code").asString()).isEqualTo("FORBIDDEN_SCOPE");
    verifyNoInteractions(h.orderService);
    when(h.orderService.previewGroup(any(), any())).thenReturn(new GroupPreviewDto(true, List.of(), List.of(),
        List.of(), new BigDecimal("500.00"), new BigDecimal("400.00"), null));
    assertThat(McpToolHarness.ok(h.call("preview_order_group", h.keyWith(Scopes.ACCOUNT_READ), Map.of(
        "group_type", "OCO", "symbol", "AAPL", "qty", "1", "take_profit_limit_price", "30.00",
        "stop_loss_stop_price", "20.00"))).get("valid").asBoolean()).isTrue();
    when(h.orders.group(1L, "grp_1")).thenReturn(Optional.of(group()));
    assertThat(McpToolHarness.ok(h.call("get_order_group", h.keyWith(Scopes.ACCOUNT_READ),
        Map.of("group_id", "grp_1"))).get("status").asString()).isEqualTo("ACTIVE");
    when(h.orders.group(1L, "grp_x")).thenReturn(Optional.empty());
    assertThat(McpToolHarness.error(h.call("get_order_group", Map.of("group_id", "grp_x"))).get("code").asString())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void groupRejectionsKeepTheLegField() {
    when(h.orderService.placeGroup(any(), any(), any())).thenThrow(ApiException.of(ErrorCode.ORDER_GROUP_INVALID,
        "The take profit (99.00) must be above the entry price (100.00).")
        .withField("takeProfit.limitPrice", ErrorCode.ORDER_GROUP_INVALID, "Above the entry"));
    JsonNode error = McpToolHarness.error(h.call("place_order_group", Map.of("client_order_id", "c-9",
        "group_type", "BRACKET", "symbol", "AAPL", "qty", "1", "entry_type", "LIMIT", "entry_limit_price", "100.00",
        "take_profit_limit_price", "99.00", "stop_loss_stop_price", "95.00")));
    assertThat(error.get("code").asString()).isEqualTo("ORDER_GROUP_INVALID");
    assertThat(error.get("fields").get(0).get("field").asString()).isEqualTo("takeProfit.limitPrice");
  }

  @Test
  void placeOrderTakesDoNotReduce() {
    when(h.orderService.place(any(), any(), any())).thenReturn(McpToolsTest.response(false));
    McpToolHarness.ok(h.call("place_order", Map.of("client_order_id", "c-dnr", "symbol", "AAPL", "side", "BUY",
        "type", "LIMIT", "qty", "1", "limit_price", "200.00", "time_in_force", "GTC", "do_not_reduce", true)));
    ArgumentCaptor<PlaceOrderRequest> req = ArgumentCaptor.forClass(PlaceOrderRequest.class);
    verify(h.orderService).place(any(), req.capture(), any());
    assertThat(req.getValue().doNotReduce()).isTrue();
  }
}
