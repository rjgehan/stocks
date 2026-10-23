package com.stocks.contract.marketdata;

import java.time.LocalDate;
import java.util.List;

/** §30: the upcoming expirations of {@code underlying}'s listed options, ascending, and the option feed. */
public record OptionExpirations(String underlying, List<LocalDate> expirations, String feed) {

  public OptionExpirations {
    expirations = expirations == null ? List.of() : List.copyOf(expirations);
  }
}
