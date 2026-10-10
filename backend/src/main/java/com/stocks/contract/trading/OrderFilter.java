package com.stocks.contract.trading;

import java.time.Instant;

public record OrderFilter(OrderStatusFilter status, String symbolOrNull, Instant fromOrNull, Instant toOrNull) {}
