package com.stocks.contract.marketdata.model;

import java.time.Instant;

/** A provider snapshot (Alpaca {@code /v2/stocks/snapshots}). */
public record Snapshot(String symbol, Trade lastTrade, Quote quote, Bar minuteBar, Bar dailyBar, Bar prevDailyBar,
                       Instant asOf) {}
