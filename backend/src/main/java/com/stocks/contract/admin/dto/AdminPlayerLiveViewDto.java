package com.stocks.contract.admin.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import java.time.Instant;
import java.util.List;

/** §21.1 {@code AdminPlayerLiveView}: the audited break-glass view (§21.3). */
public record AdminPlayerLiveViewDto(PlayerSummaryDto player, Instant viewedAt, String reason,
                                     List<PositionDto> positions, List<OrderDto> openOrders) {}
