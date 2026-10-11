package com.stocks.contract.marketdata;

import java.util.Optional;

/**
 * The eleven GICS sectors (§15.10 screener). The constant names are the API values; {@link #label} is the GICS name
 * used by {@code marketdata/sectors.csv}.
 */
public enum Sector {
  COMMUNICATION_SERVICES("Communication Services"),
  CONSUMER_DISCRETIONARY("Consumer Discretionary"),
  CONSUMER_STAPLES("Consumer Staples"),
  ENERGY("Energy"),
  FINANCIALS("Financials"),
  HEALTH_CARE("Health Care"),
  INDUSTRIALS("Industrials"),
  INFORMATION_TECHNOLOGY("Information Technology"),
  MATERIALS("Materials"),
  REAL_ESTATE("Real Estate"),
  UTILITIES("Utilities");

  private final String label;

  Sector(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  /** By GICS label or constant name, case-insensitive. */
  public static Optional<Sector> parse(String s) {
    if (s == null || s.isBlank()) {
      return Optional.empty();
    }
    String t = s.trim();
    for (Sector x : values()) {
      if (x.label.equalsIgnoreCase(t) || x.name().equalsIgnoreCase(t.replace(' ', '_').replace('-', '_'))) {
        return Optional.of(x);
      }
    }
    return Optional.empty();
  }
}
