package com.stocks.contract.security;

import java.util.Set;

/**
 * API scopes (§7.1): space-separated strings on keys and tokens. There is no admin scope (§17.4).
 */
public final class Scopes {

  public static final String MARKET_READ = "market:read";
  public static final String ACCOUNT_READ = "account:read";
  public static final String TRADE = "trade";
  public static final String LEAGUE_READ = "league:read";

  /** All four scopes (the default for admin-issued bot keys, §21.1). */
  public static final Set<String> ALL = Set.of(MARKET_READ, ACCOUNT_READ, TRADE, LEAGUE_READ);

  /** Read-only scopes (the default for personal keys, §17.2). */
  public static final Set<String> READ_ONLY = Set.of(MARKET_READ, ACCOUNT_READ, LEAGUE_READ);

  private Scopes() {}
}
