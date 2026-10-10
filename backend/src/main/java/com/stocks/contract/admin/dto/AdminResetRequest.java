package com.stocks.contract.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** {@code POST /admin/players/{handle}/reset} + {@code Idempotency-Key} (§21.1). {@code startingCash} null = default. */
public record AdminResetRequest(BigDecimal startingCash, @NotBlank String memo) {}
