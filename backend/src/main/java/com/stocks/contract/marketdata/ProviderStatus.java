package com.stocks.contract.marketdata;

import java.time.Instant;
import java.util.Map;

/** One provider's health on {@code /admin/system} (§15.8). {@code state} is the stream's {@code ConnectionState}. */
public record ProviderStatus(String id, String state, int streamSymbolsUsed, int streamSymbolsMax, int restPerMinUsed,
                             int restPerMinMax, Instant lastMessageAt, Map<String, Object> capabilities,
                             Map<String, Object> probe) {}
