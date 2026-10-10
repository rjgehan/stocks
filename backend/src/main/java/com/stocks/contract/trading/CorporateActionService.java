package com.stocks.contract.trading;

import com.stocks.contract.trading.dto.CorporateActionDto;
import java.math.BigDecimal;
import java.time.LocalDate;

public interface CorporateActionService {

  /** WS2 {@code SplitGuard}: raw vs split-adjusted prior bars differ but no APPLIED split exists (§14). Idempotent. */
  CorporateActionDto flagDetectedSplit(String symbol, LocalDate exDate, BigDecimal inferredOldRate,
                                       BigDecimal inferredNewRate);

  /**
   * Admin: APPLY (FORWARD_/REVERSE_SPLIT only in Phase 1, rates required) or SKIP a NEEDS_ADMIN action.
   *
   * @throws com.stocks.common.error.ApiException {@code CORPORATE_ACTION_NOT_RESOLVABLE}
   */
  CorporateActionDto resolve(String actionId, CorporateActionResolution resolution, BigDecimal oldRate,
                             BigDecimal newRate, String note, long actorPlayerId);
}
