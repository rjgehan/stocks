package com.stocks.contract.alerts.dto;

import com.stocks.common.domain.AlertCondition;
import com.stocks.common.domain.AlertStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phase 2 §18.3 {@code Alert} (§19.1): a price alert. {@code threshold} is a price for {@code PRICE_*} and a
 * fraction like {@code Quote.changePct} for {@code CHANGE_PCT_*} ({@code "0.05"} = +5%). {@code armed} is false
 * while a repeating alert waits to re-arm after firing.
 */
public record AlertDto(String id, String symbol, AlertCondition condition, BigDecimal threshold, boolean repeat,
                       AlertStatus status, boolean armed, String note, Instant expiresAt, int triggerCount,
                       Instant lastTriggeredAt, BigDecimal lastTriggerPrice, Instant createdAt, Instant updatedAt) {}
