package com.stocks.contract.trading;

import java.time.Instant;

public record ExecutionFilter(String symbolOrNull, Instant fromOrNull, Instant toOrNull) {}
