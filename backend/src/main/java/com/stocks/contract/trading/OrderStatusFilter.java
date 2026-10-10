package com.stocks.contract.trading;

/** {@code GET /orders?status=}: OPEN = PENDING | WORKING | PARTIALLY_FILLED. */
public enum OrderStatusFilter { OPEN, CLOSED, ALL }
