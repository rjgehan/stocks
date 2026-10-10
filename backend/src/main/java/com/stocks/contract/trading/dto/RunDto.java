package com.stocks.contract.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.stocks.common.domain.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** §18.3 {@code Run}: one account run (§16.3). {@code ret} is the JSON {@code return} (run-relative fraction). */
public record RunDto(String accountId, int runNo, AccountStatus status, Instant openedAt, Instant closedAt,
                     BigDecimal startingCash, BigDecimal finalEquity, BigDecimal netContributions,
                     @JsonProperty("return") BigDecimal ret, BigDecimal pnl) {}
