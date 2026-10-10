package com.stocks.common.events;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.ViolationType;
import java.time.Instant;

/** A good-faith violation was recorded (§5.3, §10.4). {@code violationId} is the public id ({@code vio_…}). */
public record ViolationIssued(long accountId, long playerId, String violationId, ViolationType type,
                              AccountRestriction restrictionAfter, Instant at) {}
