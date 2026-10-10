package com.stocks.contract.trading;

import com.stocks.common.domain.CancelReason;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.dto.OrderDto;

/** {@code DELETE /admin/orders/{orderId}}: cancels any player's open order (§21.1, WS3). */
public interface AdminOrderService {

  /** {@code reason} is {@link CancelReason#ADMIN}; visible to the player in the order timeline. */
  OrderDto cancel(String orderPublicId, CancelReason reason, long actorPlayerId, RequestMeta meta);
}
