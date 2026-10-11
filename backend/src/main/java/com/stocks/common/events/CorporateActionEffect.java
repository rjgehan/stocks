package com.stocks.common.events;

import com.stocks.common.domain.CorporateActionType;
import java.time.Instant;

/**
 * What one corporate action did to one account (§14), published by the engine after commit next to
 * {@link AccountChanged}: WS5 turns it into a {@code CORPORATE_ACTION} notification for the player (idempotent by
 * {@code CA:<actionId>:<accountId>:<effect>}). {@code effect}: {@code SPLIT}, {@code RENAME}, {@code DIV_ACC}
 * (dividend accrued on the ex-date), {@code DIV_PAID} or {@code ORDER_ADJ} (open orders reduced under FINRA 5330,
 * Phase 2). {@code title} and {@code body} are the player-facing text.
 */
public record CorporateActionEffect(long accountId, long playerId, String actionId, CorporateActionType type,
                                    String symbol, String effect, String title, String body, Instant at) {}
