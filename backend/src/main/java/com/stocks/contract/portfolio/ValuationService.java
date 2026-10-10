package com.stocks.contract.portfolio;

import java.util.Map;

/** Live valuation (§16.1, WS4). */
public interface ValuationService {

  /** Reads through the CALLER's transaction (JdbcClient is tx-aware), so the engine sees its own uncommitted state. */
  Valuation valueNow(long accountId);

  /** Every ACTIVE account, keyed by account id. */
  Map<Long, Valuation> valueAllActive();
}
