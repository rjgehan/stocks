package com.stocks.contract.marketdata;

/** Provider REST priority classes, highest first (§15.5 {@code ProviderRateLimiter}). */
public enum RefreshPriority { ORDER_CRITICAL, ENGINE, UI, BACKGROUND }
