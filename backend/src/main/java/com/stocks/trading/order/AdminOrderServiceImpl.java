package com.stocks.trading.order;

import com.stocks.common.domain.CancelReason;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.AdminOrderService;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /admin/orders/{orderId}} (§21.1): cancels any player's open order with {@link CancelReason#ADMIN},
 * which the player sees in the order timeline. WS1's controller audits it.
 */
@Service
public class AdminOrderServiceImpl implements AdminOrderService {

  private final EngineCalls engine;

  public AdminOrderServiceImpl(EngineCalls engine) {
    this.engine = engine;
  }

  @Override
  public OrderDto cancel(String orderPublicId, CancelReason reason, long actorPlayerId, RequestMeta meta) {
    return engine.call(new EngineCommand.CancelOrderCmd(null, orderPublicId,
        reason == null ? CancelReason.ADMIN : reason, actorPlayerId));
  }
}
