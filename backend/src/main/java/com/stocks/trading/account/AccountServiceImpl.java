package com.stocks.trading.account;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.trading.engine.EngineCalls;
import com.stocks.trading.engine.EngineCommand;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/**
 * Account lifecycle (§10): each method is one engine command; the returned {@code Account} is read after commit.
 * A reset replayed through its {@code Idempotency-Key} returns the run it opened (valued live).
 */
@Service
public class AccountServiceImpl implements AccountService {

  private final EngineCalls engine;
  private final AccountQueries queries;

  public AccountServiceImpl(EngineCalls engine, AccountQueries queries) {
    this.engine = engine;
    this.queries = queries;
  }

  @Override
  public AccountDto openInitialAccount(long playerId, BigDecimal startingCash, long actorPlayerId) {
    long accountId = engine.<Long>call(new EngineCommand.OpenAccountCmd(playerId, startingCash, actorPlayerId));
    return queries.account(accountId);
  }

  @Override
  public AccountDto reset(long playerId, BigDecimal startingCashOrNull, String memo, IdempotencyRef idem,
                          long actorPlayerId) {
    long accountId = engine.<Long>call(new EngineCommand.ResetAccountCmd(playerId, startingCashOrNull, memo, idem,
        actorPlayerId));
    return queries.account(accountId);
  }

  @Override
  public void restrict(long playerId, AccountRestriction restriction, CancelReason cancelReason, String reason,
                       long actorPlayerId) {
    engine.call(new EngineCommand.RestrictAccountCmd(playerId, restriction == null ? AccountRestriction.FROZEN
        : restriction, cancelReason == null ? CancelReason.PLAYER_FROZEN : cancelReason, reason, actorPlayerId));
  }

  @Override
  public void liftAdminRestriction(long playerId, long actorPlayerId) {
    engine.call(new EngineCommand.LiftRestrictionCmd(playerId, actorPlayerId));
  }

  @Override
  public void recordAdminView(long accountId, String reason, long actorPlayerId) {
    engine.call(new EngineCommand.RecordAdminViewCmd(accountId, reason, actorPlayerId));
  }
}
