package com.stocks.contract.trading.dto;

import com.stocks.common.domain.AccountRestriction;
import java.time.Instant;

/** {@code ActivityDetail} {@code type: "RESTRICTION"} (§10.4, §10.5). */
public record RestrictionDetailDto(AccountRestriction from, AccountRestriction to, Instant until, String reason)
    implements ActivityDetailDto {}
