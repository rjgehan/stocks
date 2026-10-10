package com.stocks.contract.trading.dto;

import com.stocks.common.domain.OrderGroupRole;
import com.stocks.common.domain.OrderGroupType;

/**
 * §18.3 {@code OrderGroupRef} (Phase 2, §11.9): the group a leg belongs to. {@code parentOrderId}: a child's entry
 * ({@code ord_…}), null for an entry or an OCO leg; {@code held}: a child its entry has not released yet.
 */
public record OrderGroupRefDto(String groupId, OrderGroupType type, OrderGroupRole role, String parentOrderId,
                               boolean held) {}
