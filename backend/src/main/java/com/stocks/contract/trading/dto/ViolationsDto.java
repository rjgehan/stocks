package com.stocks.contract.trading.dto;

import com.stocks.common.domain.AccountRestriction;
import java.time.Instant;
import java.util.List;

/** {@code GET /account/violations} and MCP {@code get_violations} body (§18.2, §20.3). */
public record ViolationsDto(AccountRestriction restriction, Instant restrictionUntil, List<ViolationDto> active,
                            List<ViolationDto> expired) {}
