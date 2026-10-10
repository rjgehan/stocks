package com.stocks.contract.trading;

import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PreviewDto;
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
}
