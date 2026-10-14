package com.stocks.contract.portfolio.dto;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.DataQuality;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.trading.dto.PositionDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code PublicPortfolio} (public mode, §16.7; {@code GET /players/{handle}/portfolio}, MCP
 * {@code get_player_portfolio}): a player's live run, valued now. {@code positions} reuse {@code Position} (no private
 * member), largest market value first; {@code openOrders} are every open order of the run. Without an active run:
 * {@code runNo} 0, zero money, null {@code marksQuality} and restriction, empty lists.
 */
public record PublicPortfolioDto(PlayerSummaryDto player, int runNo, Instant asOf, DataQuality marksQuality,
                                 BigDecimal equity, BigDecimal longMarketValue, BigDecimal dividendsReceivable,
                                 BigDecimal cash, BigDecimal settledCash, BigDecimal unsettledCash,
                                 BigDecimal buyingPower, BigDecimal openBuyHolds, BigDecimal netContributions,
                                 BigDecimal totalPnl, BigDecimal totalReturn, BigDecimal dayPnl, BigDecimal dayReturn,
                                 BigDecimal unrealizedPnl, AccountRestriction restriction, Instant restrictionUntil,
                                 int gfvActive, List<PositionDto> positions, List<PublicOrderDto> openOrders) {}
