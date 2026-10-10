package com.stocks.trading.order;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;

/** Who placed an order and through which channel ({@code trade_order} provenance columns, §8.1). */
public record OrderOrigin(long playerId, PlayerKind kind, Channel channel, Long apiKeyId, String oauthClientId,
                          String mcpClient, String clientIp) {

  public static OrderOrigin of(PlayerPrincipal who, RequestMeta meta) {
    Channel channel = meta == null || meta.channel() == null ? Channel.WEB : meta.channel();
    return new OrderOrigin(who.playerId(), who.kind(), channel, who.apiKeyId(), who.oauthClientId(),
        meta == null ? null : meta.mcpClient(), meta == null ? null : meta.ip());
  }
}
