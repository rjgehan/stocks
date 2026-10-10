package com.stocks.common.events;

import com.stocks.common.domain.CorporateActionType;
import java.time.Instant;

/** A corporate action was applied (§5.3, §14). {@code actionId} is the public id ({@code cac_…}). */
public record CorporateActionApplied(String actionId, String symbol, CorporateActionType type, Instant at) {}
