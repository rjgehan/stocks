package com.stocks.trading.account;

import com.stocks.common.domain.AccountEventType;
import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.AdminViewDetailDto;
import com.stocks.contract.trading.dto.ResetDetailDto;
import com.stocks.contract.trading.dto.RestrictionDetailDto;
import com.stocks.trading.config.TradingSettings;
import com.stocks.trading.engine.EngineTx;
import com.stocks.trading.engine.write.AccountEventWriter;
import com.stocks.trading.engine.write.AccountWriter;
import com.stocks.trading.engine.write.LedgerWriter;
import com.stocks.trading.engine.write.TransferWriter;
import com.stocks.trading.store.AccountRow;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.IdempotencyStore;
import com.stocks.trading.store.PlayerRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The account commands of §10 on the engine thread: open (run 1), reset (run n+1 at the final unit value, §16.3),
 * restrict and lift (§10.5), the break-glass view event (§21.3) and daily GFV maintenance (§10.4). Orders are
 * cancelled by the engine before {@link #reset} and {@link #restrict} run, in the same transaction.
 */
@Component
public class AccountCommands {

  public static final String RESET_SCOPE_ADMIN = "admin.reset";
  public static final String RESET_SCOPE_SELF = "account.reset";

  private final AccountStore accounts;
  private final AccountWriter accountWriter;
  private final TransferWriter transfers;
  private final LedgerWriter ledger;
  private final AccountEventWriter events;
  private final EquityValuer valuer;
  private final GfvService gfv;
  private final IdempotencyStore idempotency;
  private final TradingSettings settings;
  private final MarketDataService marketData;
  private final PublicId publicIds;
  private final JsonMapper json;
  private final String benchmarkSymbol;

  public AccountCommands(AccountStore accounts, AccountWriter accountWriter, TransferWriter transfers,
                         LedgerWriter ledger, AccountEventWriter events, EquityValuer valuer, GfvService gfv,
                         IdempotencyStore idempotency, TradingSettings settings, MarketDataService marketData,
                         PublicId publicIds, JsonMapper json,
                         @Value("${stocks.performance.benchmark-symbol:SPY}") String benchmarkSymbol) {
    this.accounts = accounts;
    this.accountWriter = accountWriter;
    this.transfers = transfers;
    this.ledger = ledger;
    this.events = events;
    this.valuer = valuer;
    this.gfv = gfv;
    this.idempotency = idempotency;
    this.settings = settings;
    this.marketData = marketData;
    this.publicIds = publicIds;
    this.json = json;
    this.benchmarkSymbol = benchmarkSymbol;
  }

  // ------------------------------------------------------------------------------------------- open

  /** Opens and funds the player's first run (idempotent: an existing ACTIVE account is returned). */
  public long open(long playerId, BigDecimal startingCash, long actorPlayerId, Instant now, EngineTx tx) {
    PlayerRow player = accounts.player(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No player " + playerId + "."));
    var existing = accounts.activeByPlayer(playerId);
    if (existing.isPresent()) {
      return existing.get().id();
    }
    BigDecimal cash = startingCash(startingCash);
    int runNo = accounts.maxRunNo(playerId) + 1;
    String publicId = publicIds.next(Prefix.ACCOUNT);
    long accountId = accountWriter.insert(publicId, player.id(), runNo, cash, 1, now, null);
    fund(accountId, cash, BigDecimal.ONE.setScale(18), Rounding.UNIT.divide(cash, BigDecimal.ONE), 1, false,
        "Initial funding", actorPlayerId == playerId ? null : actorPlayerId, now);
    tx.publish(new AccountChanged(accountId, playerId, "OPENED", now));
    return accountId;
  }

  private BigDecimal startingCash(BigDecimal requested) {
    BigDecimal cash = requested == null ? settings.defaultStartingCash() : requested;
    cash = Money.require("startingCash", cash);
    if (cash.compareTo(new BigDecimal("1.00")) < 0) {
      throw ApiException.validation("startingCash", "startingCash must be at least 1.00");
    }
    return cash;
  }

  /** The INITIAL transfer of a run (§10.1, §16.2): batch, transfer, ledger row, flow caches. */
  private void fund(long accountId, BigDecimal amount, BigDecimal unitValue, BigDecimal units, int series,
                    boolean rebased, String memo, Long actorOrNull, Instant now) {
    LocalDate today = Et.date(now);
    long batchId = transfers.insertBatch(new TransferWriter.NewBatch(publicIds.next(Prefix.TRANSFER_BATCH),
        TransferScope.SYSTEM, TransferType.INITIAL, amount, AdjustmentTreatment.CONTRIBUTION, memo, null, false,
        null, actorOrNull, now));
    transfers.setBatchCounts(batchId, 1, 0);
    String xfr = publicIds.next(Prefix.CASH_TRANSFER);
    long transferId = transfers.insertTransfer(new TransferWriter.NewTransfer(xfr, batchId, accountId,
        TransferType.INITIAL, amount, true, TransferStatus.APPLIED, null, memo, null, unitValue, units, series,
        rebased, null, spyMark(), now, actorOrNull));
    ledger.append(accountId, LedgerEntryType.INITIAL_FUNDING, amount, today, today, now, transferId, null, null, memo,
        "XFER:" + xfr);
    transfers.applyFlow(accountId, amount, units, series);
  }

  private BigDecimal spyMark() {
    try {
      Mark m = marketData.mark(benchmarkSymbol);
      return m == null ? null : m.price();
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ------------------------------------------------------------------------------------------- reset

  /** Returns the id of an earlier reset with this key (replay), after checking the request hash and the backstop. */
  public Long replayedReset(IdempotencyRef idem) {
    if (idem == null) {
      return null;
    }
    var stored = idempotency.find(idem.scope(), idem.key());
    if (stored.isPresent()) {
      if (!stored.get().requestHash().equals(idem.requestHash())) {
        throw conflict();
      }
      String accountPublicId = json.readTree(stored.get().responseBody()).get("accountId").asString();
      return accounts.byPublicId(accountPublicId).orElseThrow().id();
    }
    if (accounts.resetKeyUsed(idem.scope() + ":" + idem.key())) {
      throw conflict();
    }
    return null;
  }

  /**
   * Closes the active run at its live equity and opens run n+1 at the final unit value (§10.5, §16.3); a run that
   * ended at U = 0 rebases (U = 1, unit series + 1). Open orders were already cancelled ({@code ACCOUNT_RESET}).
   */
  public long reset(long playerId, BigDecimal startingCashOrNull, String memo, IdempotencyRef idem,
                    long actorPlayerId, Instant now, EngineTx tx) {
    AccountRow acc = accounts.activeByPlayer(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The player has no active account."));
    BigDecimal cash = startingCash(startingCashOrNull);
    String note = memo == null || memo.isBlank() ? "Account reset" : memo.trim();
    Valuation v = valuer.value(acc);
    BigDecimal finalEquity = v.equity().setScale(Money.SCALE, RoundingMode.HALF_EVEN);
    BigDecimal finalU = valuer.unitValue(acc, finalEquity);
    accountWriter.close(acc.id(), now, "RESET", finalEquity, finalU);
    accountWriter.voidAccruedDividends(acc.id(), now);

    boolean rebase = finalU.signum() == 0;
    BigDecimal u = rebase ? BigDecimal.ONE.setScale(18) : finalU;
    int series = rebase ? acc.unitSeries() + 1 : acc.unitSeries();
    String publicId = publicIds.next(Prefix.ACCOUNT);
    String scoped = idem == null ? null : idem.scope() + ":" + idem.key();
    long newId = accountWriter.insert(publicId, playerId, accounts.maxRunNo(playerId) + 1, cash, series, now, scoped);
    fund(newId, cash, u, Rounding.UNIT.divide(cash, u), series, rebase, note,
        actorPlayerId == playerId ? null : actorPlayerId, now);
    ActorKind actor = actorPlayerId == playerId ? ActorKind.SELF : ActorKind.ADMIN;
    int runNo = accounts.byId(newId).orElseThrow().runNo();
    events.insert(acc.id(), AccountEventType.RESET, now, null, actor, new ResetDetailDto(ResetDetailDto.ResetSide.CLOSED,
        acc.publicId(), publicId, acc.runNo(), finalEquity, null, note));
    events.insert(newId, AccountEventType.RESET, now, null, actor, new ResetDetailDto(ResetDetailDto.ResetSide.OPENED,
        acc.publicId(), publicId, runNo, null, cash, note));
    if (idem != null) {
      idempotency.insert(idem.scope(), idem.key(), idem.requestHash(), 200,
          json.writeValueAsString(java.util.Map.of("accountId", publicId, "runNo", runNo)), now);
    }
    tx.publish(new AccountChanged(acc.id(), playerId, "RESET", now));
    tx.publish(new AccountChanged(newId, playerId, "RESET", now));
    return newId;
  }

  private static ApiException conflict() {
    return ApiException.of(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "This Idempotency-Key was already used for a different request.")
        .withHint("Use a new Idempotency-Key for a new request.");
  }

  // ------------------------------------------------------------------------------------------- restrictions

  /** §10.5 step 2: set the admin restriction (orders were cancelled first). */
  public void restrict(long playerId, AccountRestriction restriction, String reason, Instant now, EngineTx tx) {
    AccountRow acc = activeAccount(playerId);
    if (acc.restriction() != restriction) {
      accountWriter.setRestriction(acc.id(), restriction, null, reason);
      events.insert(acc.id(), AccountEventType.RESTRICTION, now, null, ActorKind.ADMIN,
          new RestrictionDetailDto(acc.restriction(), restriction, null, reason == null ? "" : reason));
    }
    tx.publish(new AccountChanged(acc.id(), playerId, "RESTRICTION", now));
  }

  /** Drops an admin FROZEN restriction; the GFV restriction is recomputed from {@code account_violation}. */
  public void liftAdminRestriction(long playerId, Instant now, EngineTx tx) {
    AccountRow acc = activeAccount(playerId);
    if (acc.restriction() != AccountRestriction.FROZEN) {
      return;
    }
    GfvService.Recomputed r = gfv.recompute(acc.id(), now);
    String reason = r.restriction() == AccountRestriction.NONE ? "Unfrozen" : "Unfrozen; good-faith violations";
    accountWriter.setRestriction(acc.id(), r.restriction(), r.until(), reason);
    events.insert(acc.id(), AccountEventType.RESTRICTION, now, null, ActorKind.ADMIN,
        new RestrictionDetailDto(AccountRestriction.FROZEN, r.restriction(), r.until(), reason));
    tx.publish(new AccountChanged(acc.id(), playerId, "RESTRICTION", now));
  }

  /** §10.4: lifts GFV restrictions whose {@code restriction_until} has passed. Returns how many changed. */
  public int dailyMaintenance(Instant now, EngineTx tx, List<AccountRow> restricted) {
    int changed = 0;
    for (AccountRow acc : restricted) {
      if (acc.restriction() == AccountRestriction.FROZEN || acc.restriction() == AccountRestriction.NONE
          || acc.restrictionUntil() == null || acc.restrictionUntil().isAfter(now)) {
        continue;
      }
      GfvService.Recomputed r = gfv.recompute(acc.id(), now);
      if (r.restriction() != acc.restriction()) {
        accountWriter.setRestriction(acc.id(), r.restriction(), r.until(), "Restriction period ended");
        events.insert(acc.id(), AccountEventType.RESTRICTION, now, null, ActorKind.SYSTEM,
            new RestrictionDetailDto(acc.restriction(), r.restriction(), r.until(), "Restriction period ended"));
        tx.publish(new AccountChanged(acc.id(), acc.playerId(), "RESTRICTION", now));
        changed++;
      }
    }
    return changed;
  }

  /** §21.3: the player sees "Admin viewed your live account" in their activity. */
  public void recordAdminView(long accountId, String reason, long actorPlayerId, Instant now, EngineTx tx) {
    AccountRow acc = accounts.byId(accountId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "No such account."));
    String admin = accounts.player(actorPlayerId).map(PlayerRow::handle).orElse("admin");
    events.insert(acc.id(), AccountEventType.ADMIN_VIEW, now, null, ActorKind.ADMIN,
        new AdminViewDetailDto(admin, reason == null ? "" : reason));
    tx.publish(new AccountChanged(acc.id(), acc.playerId(), "ADMIN_VIEW", now));
  }

  public AccountRow activeAccount(long playerId) {
    return accounts.activeByPlayer(playerId)
        .orElseThrow(() -> ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "The player has no active account."));
  }
}
