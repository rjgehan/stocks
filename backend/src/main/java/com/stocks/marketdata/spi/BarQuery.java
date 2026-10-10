package com.stocks.marketdata.spi;

import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import java.time.Instant;
import java.util.Set;

/**
 * A provider bar request (§15.3). {@code from}/{@code to} are inclusive; {@code limit} caps the total number of bars
 * (across symbols); {@code feed} is the provider feed ({@code sip}, {@code iex}; ignored by SIM).
 */
public record BarQuery(Set<String> symbols, Timeframe tf, Instant from, Instant to, AdjustmentMode adj,
                       boolean extendedHours, int limit, String feed) {

  public BarQuery {
    symbols = Set.copyOf(symbols);
    adj = adj == null ? AdjustmentMode.RAW : adj;
  }
}
