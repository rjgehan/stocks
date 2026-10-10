package com.stocks.contract.marketdata.model;

import java.time.Instant;

/** A trading status (halt / resume) from the {@code statuses} channel; the only source of halts (§12.6). */
public record StatusEvent(String symbol, boolean halted, String reason, Instant ts) implements MarketEvent {}
