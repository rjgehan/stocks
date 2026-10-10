package com.stocks.contract.trading;

import java.time.Instant;
import java.util.List;

/** The last {@code CheckInvariantsCmd} run (§8.4). */
public record InvariantResult(Instant ranAt, boolean ok, List<String> failures) {}
