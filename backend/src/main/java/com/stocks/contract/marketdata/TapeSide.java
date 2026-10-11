package com.stocks.contract.marketdata;

/**
 * Where a Time &amp; Sales print traded against the quote in force when it was received (§15.10): {@code AT_ASK} at
 * or above the ask (buyer-initiated, shown in {@code --up}), {@code AT_BID} at or below the bid ({@code --down}),
 * {@code BETWEEN} inside the spread, {@code UNKNOWN} without a two-sided quote.
 */
public enum TapeSide { AT_ASK, AT_BID, BETWEEN, UNKNOWN }
