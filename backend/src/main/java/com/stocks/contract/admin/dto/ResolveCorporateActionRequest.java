package com.stocks.contract.admin.dto;

import com.stocks.contract.trading.CorporateActionResolution;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** {@code POST /admin/corporate-actions/{id}/resolve} (§21.1). Rates required for APPLY (scale ≤ 12). */
public record ResolveCorporateActionRequest(@NotNull CorporateActionResolution resolution, BigDecimal oldRate,
                                            BigDecimal newRate, @NotBlank String note) {}
