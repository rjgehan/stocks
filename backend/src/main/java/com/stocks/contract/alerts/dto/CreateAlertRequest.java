package com.stocks.contract.alerts.dto;

import com.stocks.common.domain.AlertCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phase 2 {@code POST /alerts} (§18.2, §19.1). {@code repeat} defaults to false (fires once); {@code expiresAt} is
 * optional (in the future, at most 365 days); {@code note} is private, at most 140 characters.
 */
public record CreateAlertRequest(@NotBlank String symbol, @NotNull AlertCondition condition,
                                 @NotNull BigDecimal threshold, Boolean repeat, Instant expiresAt, String note) {}
