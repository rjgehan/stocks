package com.stocks.marketdata.spi;

/**
 * Upstream stream state (§15.3, shown on {@code /admin/system}). {@code LIMITED}: connected but the provider refused
 * part of the subscription (symbol limit, insufficient subscription); {@code DOWN}: not configured or unreachable
 * long enough to report the feed as down.
 */
public enum ConnectionState { CONNECTING, LIVE, BACKOFF, LIMITED, CLOSED, DOWN }
