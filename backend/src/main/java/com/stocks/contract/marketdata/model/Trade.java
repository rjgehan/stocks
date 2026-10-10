package com.stocks.contract.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A print. {@code eligible} = usable for fills and triggers after condition-code filtering (§28 #11). */
public record Trade(String symbol, BigDecimal price, long size, Instant ts, Instant observedAt, String venue,
                    List<String> conditions, boolean consolidated, boolean eligible, String id, String source) {}
