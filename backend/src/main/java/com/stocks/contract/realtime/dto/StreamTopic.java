package com.stocks.contract.realtime.dto;

import com.stocks.contract.security.Scopes;
import java.util.Locale;

/** The topics of the SSE stream (§19.3) and the scope each needs. Inputs are case-insensitive. */
public enum StreamTopic {
  QUOTES(Scopes.MARKET_READ),
  SESSION(Scopes.MARKET_READ),
  ORDERS(Scopes.ACCOUNT_READ),
  ACCOUNT(Scopes.ACCOUNT_READ),
  LEADERBOARD(Scopes.LEAGUE_READ),
  FEED(Scopes.LEAGUE_READ);

  private final String scope;

  StreamTopic(String scope) {
    this.scope = scope;
  }

  public String scope() {
    return scope;
  }

  /** The topic named {@code raw} (any case), or null. */
  public static StreamTopic parse(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return valueOf(raw.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
