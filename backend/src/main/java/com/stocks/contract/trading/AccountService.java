package com.stocks.contract.trading;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.contract.trading.dto.AccountDto;
import java.math.BigDecimal;

/** Account lifecycle (§10, WS3). Each method is one engine command. */
public interface AccountService {

  /** Run 1: opens and funds the player's first account. */
  AccountDto openInitialAccount(long playerId, BigDecimal startingCash, long actorPlayerId);

  /** Closes the active run and opens the next one (§10.5, §16.3); idempotent through {@code idem}. */
  AccountDto reset(long playerId, BigDecimal startingCashOrNull, String memo, IdempotencyRef idem, long actorPlayerId);

  /** RestrictAccountCmd: cancel open orders with {@code cancelReason}, set the restriction (FROZEN). Called by WS1 (§10.5). */
  void restrict(long playerId, AccountRestriction restriction, CancelReason cancelReason, String reason,
                long actorPlayerId);

  /** LiftRestrictionCmd: drop the admin restriction; recompute any GFV restriction from {@code account_violation} (§10.4). */
  void liftAdminRestriction(long playerId, long actorPlayerId);

  /**
   * Break-glass live view (§21.3): writes an {@code ADMIN_VIEW} {@code account_event}. No caller since public mode;
   * kept for the historical ADMIN_VIEW rows.
   */
  void recordAdminView(long accountId, String reason, long actorPlayerId);
}
