package com.stocks.contract.trading;

import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.dto.GroupPreviewDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.OrderGroupDto;
import com.stocks.contract.trading.dto.PlaceOrderGroupRequest;
import com.stocks.contract.trading.dto.PlaceOrderGroupResponse;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
import com.stocks.contract.trading.dto.ReplaceOrderGroupRequest;
import com.stocks.contract.trading.dto.ReplaceOrderRequest;
import java.util.List;

/** Order entry on the request thread: {@code OrderGate}, then the engine (§11.2, WS3). Used by REST and MCP. */
public interface OrderService {

  PlaceOrderResponse place(PlayerPrincipal who, PlaceOrderRequest req, RequestMeta meta);

  /** Checks 1–3 and 7–27 against committed state; never charges a write token; always returns (§18.2). */
  PreviewDto preview(PlayerPrincipal who, PlaceOrderRequest req);

  PlaceOrderResponse replace(PlayerPrincipal who, String orderId, ReplaceOrderRequest req, RequestMeta meta);

  OrderDto cancel(PlayerPrincipal who, String orderId, RequestMeta meta);

  List<OrderDto> cancelAll(PlayerPrincipal who, String symbolOrNull, RequestMeta meta);

  PlaceOrderResponse closePosition(PlayerPrincipal who, String symbol, String clientOrderId, String rationale,
                                   RequestMeta meta);

  // ---- group orders (Phase 2, §11.9)

  /** {@code POST /order-groups}: every leg or nothing; idempotent by the group's {@code clientOrderId}. */
  PlaceOrderGroupResponse placeGroup(PlayerPrincipal who, PlaceOrderGroupRequest req, RequestMeta meta);

  /** {@code POST /order-groups/preview}: checks 1–3, the group rules and every leg; always returns. */
  GroupPreviewDto previewGroup(PlayerPrincipal who, PlaceOrderGroupRequest req);

  /** {@code PATCH /order-groups/{groupId}}: one cancel-replace of the changed legs, with a NEW group id. */
  PlaceOrderGroupResponse replaceGroup(PlayerPrincipal who, String groupId, ReplaceOrderGroupRequest req,
                                       RequestMeta meta);

  /** {@code DELETE /order-groups/{groupId}}: every open leg (children first; §11.10 latency applies). */
  OrderGroupDto cancelGroup(PlayerPrincipal who, String groupId, RequestMeta meta);
}
