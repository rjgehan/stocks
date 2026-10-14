package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * §18.3 {@code PublicTrade}: another player's trade after {@code social.tradeFeedDelay} (none by default).
 * {@code viaApi}: placed with an API key or MCP. The rationale is public when {@code trade_order.rationale_public}
 * (every order since public mode).
 */
public record PublicTradeDto(Instant executedAt, String symbol, Side side, BigDecimal qty, BigDecimal price,
                             BigDecimal realizedPnl, boolean viaApi, UntrustedRationaleDto untrusted) {

  public record UntrustedRationaleDto(String rationale) {}
}
