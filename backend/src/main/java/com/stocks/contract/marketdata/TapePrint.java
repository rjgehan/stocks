package com.stocks.contract.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One print of the Time &amp; Sales tape (§15.10), raw (as traded). {@code id} is opaque and unique per print within
 * the tape (clients merge REST and STOMP batches by it). Only prints a stream delivered are on the tape: the snapshot
 * poller's sampled "latest trade" never is, so the tape is never a fabricated or sampled sequence.
 */
public record TapePrint(String id, BigDecimal price, long size, Instant ts, String venue, boolean consolidated,
                        TapeSide side) {}
