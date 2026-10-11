package com.stocks.contract.alerts.dto;

import com.stocks.common.domain.AlertCondition;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Phase 2 {@code PUT /alerts/{alertId}} (§18.2, §19.1): replaces every editable field (a missing {@code expiresAt}
 * or {@code note} clears it; a missing {@code repeat} is false) and re-arms the alert.
 */
public record UpdateAlertRequest(@NotNull AlertCondition condition, @NotNull BigDecimal threshold, Boolean repeat,
                                 Instant expiresAt, String note) {}
