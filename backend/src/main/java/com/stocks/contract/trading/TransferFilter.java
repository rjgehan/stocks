package com.stocks.contract.trading;

import java.time.Instant;

public record TransferFilter(String handleOrNull, String batchIdOrNull, Instant fromOrNull, Instant toOrNull) {}
