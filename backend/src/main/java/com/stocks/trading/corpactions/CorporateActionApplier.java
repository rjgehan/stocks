package com.stocks.trading.corpactions;

import com.stocks.common.domain.AccountEventType;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.AdminAlertRaised;
import com.stocks.common.events.CorporateActionApplied;
import com.stocks.common.events.CorporateActionEffect;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Prices;
import com.stocks.common.money.Qty;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.Et;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.marketdata.Mark;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.SplitAdjustments;
import com.stocks.contract.marketdata.model.Asset;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.NameChangeDetailDto;
import com.stocks.contract.trading.dto.SplitDetailDto;
import com.stocks.contract.watchlist.WatchlistService;
import com.stocks.trading.engine.EngineOrderOps;
import com.stocks.trading.engine.EngineThread;
import com.stocks.trading.engine.EngineTx;
import com.stocks.trading.engine.write.AccountEventWriter;
import com.stocks.trading.engine.write.AdjustmentWriter;
import com.stocks.trading.engine.write.DividendWriter;
import com.stocks.trading.engine.write.LedgerWriter;
import com.stocks.trading.engine.write.PositionWriter;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.LotRow;
import com.stocks.trading.store.PositionRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Applies corporate actions on the engine thread (§14), inside the command's one transaction: every per-account
 * effect (one {@code position_adjustment} per affected lot, any CASH_IN_LIEU or DIVIDEND ledger row, one
 * {@code account_event} per account) commits together with the action's status, or not at all.
 * <ul>
 *   <li>{@link #apply}: {@code ApplyCorporateActionCmd} at 03:45 ET on the ex-date (NAME_CHANGE, then splits, then
 *       dividends); types Phase 1 cannot apply go to NEEDS_ADMIN with an admin alert.</li>
 *   <li>{@link #resolve}: {@code ResolveCorporateActionCmd}, the admin's APPLY (a split, as of now, with confirmed
 *       rates) or SKIP of a NEEDS_ADMIN action.</li>
 *   <li>{@link #payDividends}: {@code PayDividendsCmd} at 06:00 ET: DIVIDEND ledger rows (settled at once) for every
 *       ACCRUED entitlement whose payable date has come; {@code paid_at} = the row's {@code posted_at} (§16.1).</li>
 * </ul>
 */
@Component
public class CorporateActionApplier {

  private static final Logger log = LoggerFactory.getLogger(CorporateActionApplier.class);
  /** One holder of a symbol: an ACTIVE account, its player and its quantity. */
  record Holder(long accountId, long playerId, BigDecimal qty) {}

  private final CorporateActionStore store;
  private final AccountStore accounts;
  private final PositionWriter positions;
  private final AdjustmentWriter adjustments;
  private final LedgerWriter ledger;
  private final AccountEventWriter events;
  private final DividendWriter dividends;
  private final EngineOrderOps orders;
  private final SplitAdjustments splits;
  private final MarketDataService marketData;
  private final WatchlistService watchlists;
  private final TradingClock clock;
  private final JdbcClient jdbc;

  public CorporateActionApplier(CorporateActionStore store, AccountStore accounts, PositionWriter positions,
                                AdjustmentWriter adjustments, LedgerWriter ledger, AccountEventWriter events,
                                DividendWriter dividends, EngineOrderOps orders, SplitAdjustments splits,
                                MarketDataService marketData, WatchlistService watchlists, TradingClock clock,
                                JdbcClient jdbc) {
    this.store = store;
    this.accounts = accounts;
    this.positions = positions;
    this.adjustments = adjustments;
    this.ledger = ledger;
    this.events = events;
    this.dividends = dividends;
    this.orders = orders;
    this.splits = splits;
    this.marketData = marketData;
    this.watchlists = watchlists;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- apply (03:45)

  /**
   * Applies a PENDING action; anything else is returned unchanged (idempotent: the job may replay it).
   *
   * @throws ApiException {@code NOT_FOUND} for an unknown id
   */
  public CorporateActionRow apply(String actionId, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    CorporateActionRow ca = find(actionId);
    if (ca.status() != CorporateActionStatus.PENDING) {
      return ca;
    }
    if (Et.date(now).isBefore(ca.exDate())) {
      // never before its ex-date (an admin "Run now" with a future run key): it stays PENDING until then
      log.warn("Corporate action {} {} {} is not due before its ex-date {}; left PENDING", ca.publicId(), ca.symbol(),
          ca.type(), ca.exDate());
      return ca;
    }
    String detail = null;
    switch (ca.type()) {
      case NAME_CHANGE -> {
        String target = normalize(ca.newSymbol());
        if (target == null || target.equals(ca.symbol())) {
          return needsAdmin(ca, "NAME_CHANGE without a different new symbol", now, tx);
        }
        boolean traded = tradedSinceEx(ca.symbol(), ca.exDate(), now);
        rename(ca, target, ActorKind.SYSTEM, now, traded ? now : nominalTime(ca.exDate(), now), tx);
      }
      case FORWARD_SPLIT, REVERSE_SPLIT -> {
        if (!validRates(ca.oldRate(), ca.newRate())) {
          return needsAdmin(ca, "split without valid rates (" + ca.ratio() + ")", now, tx);
        }
        Optional<CorporateActionRow> applied = appliedSplit(ca);
        if (applied.isPresent()) {
          return supersede(ca, applied.get(), now);
        }
        Instant cutoff = exCutoff(ca.exDate());
        if (!now.isBefore(cutoff) && holdersAt(ca.symbol(), cutoff).isEmpty()) {
          // found (or applied) after its ex-date, and nobody held the symbol then: shares bought since were bought
          // at post-split prices, so only the price history and the orders entered before the ex-date change
          int cancelled = orders.cancelAllOnSymbol(ca.symbol(), CancelReason.CORPORATE_ACTION, 0, now, cutoff, tx)
              .size();
          splits.record(ca.symbol(), ca.exDate(), ca.oldRate(), ca.newRate(), ca.id());
          detail = CorporateActionStore.appendDetail(ca.detail(), "applied after its ex-date: nobody held "
              + ca.symbol() + " at the ex cut-off, so only prices before the ex-date were adjusted"
              + (cancelled > 0 ? " and " + cancelled + " order(s) entered before it were cancelled" : ""));
          break;
        }
        if (tradedSinceEx(ca.symbol(), ca.exDate(), now)) {
          return needsAdmin(ca, LATE_AND_TRADED, now, tx);
        }
        // on time (03:45), or late without a trade since the cut-off (catch-up after downtime over the ex-date):
        // the effect is dated at the nominal 03:45, so the holdings replayed for the ex-date are post-split
        String problem = split(ca, ca.oldRate(), ca.newRate(), ActorKind.SYSTEM, now, nominalTime(ca.exDate(), now),
            false, tx);
        if (problem != null) {
          return needsAdmin(ca, problem, now, tx);
        }
      }
      case CASH_DIVIDEND -> {
        if (ca.cashRate() == null || ca.cashRate().signum() <= 0 || ca.payableDate() == null) {
          return needsAdmin(ca, "cash dividend without a positive rate and a payable date", now, tx);
        }
        accrue(ca, now, tx);
      }
      default -> {
        return needsAdmin(ca, ca.type() + " is not applied automatically in Phase 1 (apply it by hand, then SKIP)",
            now, tx);
      }
    }
    return applied(ca, CorporateActionStatus.PENDING, detail, null, now, tx);
  }

  /** The detail (and admin alert) of a split found after its ex-date when its symbol has traded since. */
  static final String LATE_AND_TRADED = "found after its ex-date's pre-open, and the symbol has traded since:"
      + " confirm the rates to apply it. APPLY splits the shares each account held at the ex cut-off, as of now;"
      + " shares bought since the ex-date are not changed";

  // ------------------------------------------------------------------------------------------- resolve (admin)

  /**
   * The admin's decision on a NEEDS_ADMIN action (§21.1). APPLY: forward and reverse splits only (Phase 1), with
   * positive, different confirmed rates (the type follows from them), applied as of now with the same arithmetic.
   * SKIP: any type; releases a corporate-action hold.
   *
   * @throws ApiException {@code NOT_FOUND}, {@code CORPORATE_ACTION_NOT_RESOLVABLE}, {@code VALIDATION_FAILED}
   */
  public CorporateActionRow resolve(String actionId, CorporateActionResolution resolution, BigDecimal oldRate,
                                    BigDecimal newRate, String note, long actorPlayerId, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    CorporateActionRow ca = find(actionId);
    if (ca.status() != CorporateActionStatus.NEEDS_ADMIN) {
      throw ApiException.of(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE,
              "Corporate action " + ca.publicId() + " is " + ca.status() + ", not NEEDS_ADMIN.")
          .withHint("Only actions that need the admin can be applied or skipped.");
    }
    String by = store.handleOf(actorPlayerId).orElse("admin");
    String noteText = note == null ? "" : ": " + note;
    if (resolution == CorporateActionResolution.SKIP) {
      if (!store.transition(ca.id(), CorporateActionStatus.NEEDS_ADMIN, CorporateActionStatus.SKIPPED,
          CorporateActionStore.appendDetail(ca.detail(), "SKIP by " + by + noteText), null, actorPlayerId)) {
        throw notResolvable(ca);
      }
      log.info("Corporate action {} {} {} skipped by {}", ca.publicId(), ca.symbol(), ca.type(), by);
      return store.byId(ca.id()).orElseThrow();
    }
    if (!ca.isSplit()) {
      throw ApiException.of(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE,
              ca.type() + " cannot be applied automatically in Phase 1.")
          .withHint("Apply its effect by hand (cash transfers with treatAs=PNL_CORRECTION), then SKIP it.");
    }
    if (!validRates(oldRate, newRate)) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, "APPLY needs positive, different oldRate and newRate.")
          .withField("oldRate", ErrorCode.VALIDATION_FAILED, "> 0 and different from newRate")
          .withField("newRate", ErrorCode.VALIDATION_FAILED, "> 0 and different from oldRate");
    }
    Optional<CorporateActionRow> applied = appliedSplit(ca);
    if (applied.isPresent()) {
      throw ApiException.of(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE,
              ca.symbol() + " already has a split applied on " + ca.exDate() + " (" + applied.get().publicId() + ").")
          .withHint("SKIP this one instead.");
    }
    CorporateActionType type = newRate.compareTo(oldRate) > 0 ? CorporateActionType.FORWARD_SPLIT
        : CorporateActionType.REVERSE_SPLIT;
    store.setSplitRates(ca.id(), type, oldRate, newRate);
    CorporateActionRow confirmed = store.byId(ca.id()).orElseThrow();
    if (Et.date(now).isBefore(ca.exDate())) {
      // confirmed before its ex-date: it applies at 03:45 ET on the ex-date, like any provider split
      if (!store.transition(ca.id(), CorporateActionStatus.NEEDS_ADMIN, CorporateActionStatus.PENDING,
          CorporateActionStore.appendDetail(ca.detail(), "rates " + confirmed.ratio() + " confirmed by " + by
              + noteText + "; applies at 03:45 ET on " + ca.exDate()), null, actorPlayerId)) {
        throw notResolvable(ca);
      }
      log.info("Corporate action {} {} rates confirmed by {}; applies on {}", ca.publicId(), ca.symbol(), by,
          ca.exDate());
      return store.byId(ca.id()).orElseThrow();
    }
    String problem = split(confirmed, confirmed.oldRate(), confirmed.newRate(), ActorKind.ADMIN, now, now, true, tx);
    if (problem != null) {
      throw ApiException.of(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE, problem);
    }
    return applied(confirmed, CorporateActionStatus.NEEDS_ADMIN,
        CorporateActionStore.appendDetail(ca.detail(), "APPLY " + confirmed.ratio() + " by " + by + noteText),
        actorPlayerId, now, tx);
  }

  // ------------------------------------------------------------------------------------------- dividends (06:00)

  /** Pays every ACCRUED entitlement with {@code payable_date ≤ date}; returns how many were paid. */
  public int payDividends(LocalDate date, Instant now, EngineTx tx) {
    EngineThread.assertCurrent();
    if (date.isAfter(Et.date(now))) {
      date = Et.date(now);                          // never pay ahead of the payable date (a future run key)
    }
    record Due(long id, long caId, String caPublicId, long accountId, long playerId, String accountStatus,
               String symbol, BigDecimal qty, BigDecimal amount, BigDecimal rate) {}
    List<Due> due = jdbc.sql("""
            SELECT d.id, d.corporate_action_id, c.public_id, d.account_id, a.player_id, a.status, d.symbol,
                   d.eligible_qty, d.amount, c.cash_rate
              FROM dividend_entitlement d
              JOIN account a ON a.id = d.account_id
              JOIN corporate_action c ON c.id = d.corporate_action_id
             WHERE d.status = 'ACCRUED' AND d.payable_date <= ?
             ORDER BY d.id""")
        .param(date)
        .query((rs, n) -> new Due(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
            rs.getString(6), rs.getString(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10)))
        .list();
    int paid = 0;
    for (Due d : due) {
      if (!"ACTIVE".equals(d.accountStatus())) {
        dividends.markVoid(d.id(), now);           // its run closed without the reset voiding it
        continue;
      }
      String memo = clip(d.symbol() + " dividend $" + plain(d.rate()) + "/share × " + plain(d.qty()));
      LedgerWriter.Posted posted = ledger.append(d.accountId(), LedgerEntryType.DIVIDEND, d.amount(), date, date,
          now, null, null, d.id(), d.caId(), d.symbol(), memo, "DIV:" + d.id());
      dividends.markPaid(d.id(), now);
      paid++;
      tx.publish(new AccountChanged(d.accountId(), d.playerId(), "DIVIDEND", now));
      tx.publish(new CorporateActionEffect(d.accountId(), d.playerId(), d.caPublicId(),
          CorporateActionType.CASH_DIVIDEND, d.symbol(), "DIV_PAID", "Dividend paid: " + d.symbol(),
          "$" + d.amount().toPlainString() + " from " + plain(d.qty()) + " " + d.symbol() + " shares was added to"
              + " your cash (balance $" + posted.balanceAfter().toPlainString() + ").", now));
    }
    if (paid > 0) {
      log.info("Paid {} dividend(s) due by {}", paid, date);
    }
    return paid;
  }

  // ------------------------------------------------------------------------------------------- splits

  /** One holder's planned split: the arithmetic is done for every holder before the first write. */
  private record Plan(Holder holder, SplitMath.Result result, BigDecimal qtyAtCutoff) {}

  /**
   * The split of §14; null on success, else why it cannot be applied (then nothing was written: every position is
   * computed before the first write). Its position effects are dated {@code effectiveAt} ({@code position_adjustment}
   * and {@code account_event}; ledger rows are posted now).
   * <ul>
   *   <li>Normally the holders are the current positions, which before the ex cut-off (or without a trade since) are
   *       the holdings at the cut-off.</li>
   *   <li>{@code asOfCutoff} (the admin's APPLY after the cut-off): the holders are the accounts that held the symbol
   *       at the cut-off. An account that has not traded it since is split as usual; one that has gets only the
   *       difference its cut-off quantity is owed ({@link SplitMath#applyDelta}), so shares bought after the ex-date
   *       at post-split prices are never split. Accounts that bought only after the cut-off are not touched.</li>
   * </ul>
   * Open orders entered before the cut-off are cancelled; orders entered after it were priced post-split and stay.
   */
  private String split(CorporateActionRow ca, BigDecimal oldRate, BigDecimal newRate, ActorKind actor, Instant now,
                       Instant effectiveAt, boolean asOfCutoff, EngineTx tx) {
    String symbol = ca.symbol();
    Instant cutoff = exCutoff(ca.exDate());
    boolean fractionable = marketData.asset(symbol).map(Asset::fractionable).orElse(true);
    BigDecimal price = rawReferencePrice(symbol, ca.exDate(), oldRate, newRate, now);
    boolean late = asOfCutoff && !now.isBefore(cutoff);
    List<Holder> holders = late ? holdersAt(symbol, cutoff) : holders(symbol);
    // validate every position first, so a missing price never leaves half the accounts split
    List<Plan> plans = new ArrayList<>();
    for (Holder h : holders) {
      List<LotRow> lots = accounts.openLots(h.accountId(), symbol);
      List<SplitMath.Lot> in = lots.stream()
          .map(l -> new SplitMath.Lot(l.id(), l.qtyRemaining(), l.qtyOriginal(), l.costRemaining())).toList();
      BigDecimal current = lots.stream().map(LotRow::qtyRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
      boolean untouched = !late || (current.compareTo(h.qty()) == 0 && !tradedSince(h.accountId(), symbol, cutoff));
      try {
        plans.add(new Plan(h, untouched ? SplitMath.apply(in, oldRate, newRate, fractionable, price)
            : SplitMath.applyDelta(in, h.qty(), oldRate, newRate, fractionable, price), untouched ? null : h.qty()));
      } catch (NullPointerException e) {
        return "no reference price for the cash in lieu of " + symbol;
      } catch (IllegalStateException e) {
        return store.handleOf(h.playerId()).map(x -> "@" + x).orElse("account " + h.accountId())
            + " cannot be adjusted: " + e.getMessage() + " (SKIP the split and correct the account by hand)";
      }
    }
    orders.cancelAllOnSymbol(symbol, CancelReason.CORPORATE_ACTION, 0, now, cutoff, tx);
    LocalDate tradeDate = tradingDateOnOrAfter(Et.date(now));
    LocalDate settle = clock.settlementDate(tradeDate);
    for (Plan plan : plans) {
      Holder h = plan.holder();
      SplitMath.Result r = plan.result();
      for (SplitMath.LotResult l : r.lots()) {
        positions.splitLot(l.id(), l.qtyOriginalAfter(), l.qtyAfter(), l.costAfter(), now);
      }
      positions.setPosition(h.accountId(), symbol, r.qtyAfter(), r.costAfter(), now);
      Long cilId = null;
      if (r.cashInLieu().signum() > 0) {
        String memo = clip("Cash in lieu of " + plain(r.cashInLieuQty()) + " " + symbol + " (" + ca.ratio()
            + " split); realized P&L $" + r.realizedPnl().toPlainString());
        cilId = ledger.append(h.accountId(), LedgerEntryType.CASH_IN_LIEU, r.cashInLieu(), tradeDate, settle, now,
            null, null, null, ca.id(), symbol, memo, "CIL:" + ca.id() + ":" + h.accountId()).id();
      }
      for (SplitMath.LotResult l : r.lots()) {
        adjustments.insert(new AdjustmentWriter.NewAdjustment(h.accountId(), ca.id(), l.id(), symbol, symbol,
            l.qtyBefore(), l.qtyAfter(), l.costBefore(), l.costAfter(), l.absorbedCashInLieu() ? cilId : null,
            effectiveAt));
      }
      events.insert(h.accountId(), AccountEventType.SPLIT, effectiveAt, symbol, actor, new SplitDetailDto(
          ca.publicId(), oldRate, newRate, r.qtyBefore(), r.qtyAfter(), r.costAfter(), r.cashInLieu(),
          r.cashInLieuQty(), r.realizedPnl()));
      assertI5(h.accountId(), symbol);
      tx.publish(new AccountChanged(h.accountId(), h.playerId(), "CORPORATE_ACTION", now));
      String cash = r.cashInLieu().signum() > 0 ? " $" + r.cashInLieu().toPlainString() + " was paid in cash for "
          + plain(r.cashInLieuQty()) + " fractional shares." : "";
      String body = plan.qtyAtCutoff() == null
          ? "Your " + plain(r.qtyBefore()) + " shares are now " + plain(r.qtyAfter()) + " shares; your cost basis is"
              + " unchanged" + (cash.isEmpty() ? "." : " except for the cash in lieu." + cash)
          : "The split applies to the " + plain(plan.qtyAtCutoff()) + " shares you held at its ex-date ("
              + ca.exDate() + "); shares bought since are unchanged. Your " + plain(r.qtyBefore())
              + " shares are now " + plain(r.qtyAfter()) + "." + cash;
      tx.publish(new CorporateActionEffect(h.accountId(), h.playerId(), ca.publicId(), ca.type(), symbol, "SPLIT",
          symbol + " " + ca.ratio() + (ca.type() == CorporateActionType.REVERSE_SPLIT ? " reverse" : "") + " split",
          body, now));
    }
    splits.record(symbol, ca.exDate(), oldRate, newRate, ca.id());
    return null;
  }

  /**
   * The raw close of one pre-split share, for cash in lieu (§14): the day-end mark of the trading day before the
   * ex-date, under the symbol or, when a NAME_CHANGE moved positions onto it, under its former symbol; else the official
   * previous close when it is dated before the ex-date. A price observed on or after the ex-date is a post-split price:
   * it is converted back to one old share ({@code × newRate / oldRate}), never used as is. Null when there is none (the
   * split then waits for the admin).
   */
  private BigDecimal rawReferencePrice(String symbol, LocalDate exDate, BigDecimal oldRate, BigDecimal newRate,
                                      Instant now) {
    List<String> names = namesOf(symbol, exDate);
    try {
      LocalDate before = clock.calendar().previousTradingDay(exDate);
      for (String s : names) {
        Optional<BigDecimal> dayEnd = marketData.dayEndMark(s, before);
        if (dayEnd.isPresent() && dayEnd.get().signum() > 0) {
          return dayEnd.get();
        }
      }
    } catch (RuntimeException e) {
      log.debug("No day-end mark for {}: {}", symbol, e.toString());
    }
    try {
      LocalDate closeDate = clock.calendar().previousTradingDay(clock.calendar().currentPnlDay(now));
      for (String s : names) {
        Optional<BigDecimal> prev = marketData.previousClose(s);
        if (prev.isPresent() && prev.get().signum() > 0) {
          return closeDate.isBefore(exDate) ? prev.get() : oldShare(prev.get(), oldRate, newRate);
        }
      }
      Mark mark = marketData.mark(symbol);
      if (mark.price() == null || mark.price().signum() <= 0) {
        return null;
      }
      boolean preEx = mark.asOf() != null && mark.asOf().isBefore(exCutoff(exDate));
      return preEx ? mark.price() : oldShare(mark.price(), oldRate, newRate);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A post-split price converted back to one pre-split share. */
  private static BigDecimal oldShare(BigDecimal postSplit, BigDecimal oldRate, BigDecimal newRate) {
    return postSplit.multiply(newRate).divide(oldRate, Prices.SCALE, RoundingMode.HALF_EVEN);
  }

  /** {@code symbol}, then the symbols APPLIED NAME_CHANGEs (ex-date ≤ {@code exDate}) moved onto it, newest first. */
  private List<String> namesOf(String symbol, LocalDate exDate) {
    List<String> out = new ArrayList<>(List.of(symbol));
    for (int i = 0; i < out.size() && out.size() < 4; i++) {
      for (String former : jdbc.sql("""
              SELECT symbol FROM corporate_action
               WHERE action_type = 'NAME_CHANGE' AND status = 'APPLIED' AND new_symbol = ? AND ex_date <= ?
               ORDER BY ex_date DESC, id DESC""")
          .params(out.get(i), exDate).query(String.class).list()) {
        if (!out.contains(former)) {
          out.add(former);
        }
      }
    }
    return out;
  }

  /**
   * When a corporate action's effect is dated: now when applied before the ex cut-off, else (applied late, nothing
   * traded since) the nominal 03:45 ET of the ex-date, so the replayed holdings at the cut-off already carry it.
   */
  Instant nominalTime(LocalDate exDate, Instant now) {
    Instant cutoff = exCutoff(exDate);
    if (now.isBefore(cutoff)) {
      return now;
    }
    Instant nominal = Et.at(exDate, CorporateActionApplyJob.AT);
    return nominal.isBefore(cutoff) ? nominal : cutoff.minusSeconds(1);
  }

  /** True if the account traded {@code symbol} at or after {@code cutoff}. */
  private boolean tradedSince(long accountId, String symbol, Instant cutoff) {
    return jdbc.sql("SELECT COUNT(*) FROM execution WHERE account_id = ? AND symbol = ? AND executed_at >= ?")
        .params(accountId, symbol, Jdbc.ts(cutoff)).query(Integer.class).single() > 0;
  }

  /** True if any execution on {@code symbol} happened at or after the ex-date's pre-open (a split applied late). */
  private boolean tradedSinceEx(String symbol, LocalDate exDate, Instant now) {
    Instant cutoff = exCutoff(exDate);
    if (now.isBefore(cutoff)) {
      return false;
    }
    return jdbc.sql("SELECT COUNT(*) FROM execution WHERE symbol = ? AND executed_at >= ?")
        .params(symbol, Jdbc.ts(cutoff)).query(Integer.class).single() > 0;
  }

  private Optional<CorporateActionRow> appliedSplit(CorporateActionRow ca) {
    return store.splitsOn(ca.symbol(), ca.exDate()).stream()
        .filter(o -> o.id() != ca.id() && o.status() == CorporateActionStatus.APPLIED).findFirst();
  }

  // ------------------------------------------------------------------------------------------- name changes

  private void rename(CorporateActionRow ca, String target, ActorKind actor, Instant now, Instant effectiveAt,
                      EngineTx tx) {
    String from = ca.symbol();
    ensureAsset(from, target, now);
    orders.cancelAllOnSymbol(from, CancelReason.CORPORATE_ACTION, 0, now, null, tx);
    for (Holder h : holders(from)) {
      for (LotRow l : accounts.openLots(h.accountId(), from)) {
        adjustments.insert(new AdjustmentWriter.NewAdjustment(h.accountId(), ca.id(), l.id(), from, target,
            l.qtyRemaining(), l.qtyRemaining(), l.costRemaining(), l.costRemaining(), null, effectiveAt));
      }
      positions.renamePosition(h.accountId(), from, target, now);
      events.insert(h.accountId(), AccountEventType.NAME_CHANGE, effectiveAt, target, actor,
          new NameChangeDetailDto(ca.publicId(), from, target, h.qty()));
      assertI5(h.accountId(), target);
      tx.publish(new AccountChanged(h.accountId(), h.playerId(), "CORPORATE_ACTION", now));
      tx.publish(new CorporateActionEffect(h.accountId(), h.playerId(), ca.publicId(), ca.type(), target, "RENAME",
          from + " is now " + target, "Your " + plain(h.qty()) + " " + from + " shares now trade as " + target
              + "; quantity and cost are unchanged.", now));
    }
    watchlists.renameSymbol(from, target);
  }

  /** NAME_CHANGE needs an {@code asset} row for the new symbol: copied from the old one when missing (§14). */
  private void ensureAsset(String from, String target, Instant now) {
    int exists = jdbc.sql("SELECT COUNT(*) FROM asset WHERE symbol = ?").param(target).query(Integer.class).single();
    if (exists > 0) {
      return;
    }
    int copied = jdbc.sql("""
            INSERT INTO asset (symbol, name, exchange, asset_type, tradable, fractionable, shortable, easy_to_borrow,
                               marginable, liquidity_tier, avg_dollar_volume, status, source, updated_at)
            SELECT ?, name, exchange, asset_type, tradable, fractionable, shortable, easy_to_borrow, marginable,
                   liquidity_tier, avg_dollar_volume, 'ACTIVE', source, ?
              FROM asset WHERE symbol = ?""")
        .params(target, Jdbc.ts(now), from).update();
    if (copied == 0) {
      boolean fractionable = marketData.asset(from).map(Asset::fractionable).orElse(false);
      jdbc.sql("""
              INSERT INTO asset (symbol, name, tradable, fractionable, status, source, updated_at)
              VALUES (?, ?, TRUE, ?, 'ACTIVE', 'manual', ?)""")
          .params(target, target, fractionable, Jdbc.ts(now)).update();
    }
  }

  // ------------------------------------------------------------------------------------------- dividends

  private void accrue(CorporateActionRow ca, Instant now, EngineTx tx) {
    Instant cutoff = exCutoff(ca.exDate());
    List<Holder> holders = now.isBefore(cutoff) ? holders(ca.symbol()) : holdersAt(ca.symbol(), cutoff);
    int n = 0;
    for (Holder h : holders) {
      BigDecimal amount = Rounding.DIVIDEND.apply(h.qty().multiply(ca.cashRate()));
      if (amount.signum() <= 0) {
        continue;                                   // a sliver of a share earns less than a cent
      }
      dividends.accrue(ca.id(), h.accountId(), ca.symbol(), h.qty(), amount, ca.payableDate(), now);
      n++;
      tx.publish(new AccountChanged(h.accountId(), h.playerId(), "CORPORATE_ACTION", now));
      tx.publish(new CorporateActionEffect(h.accountId(), h.playerId(), ca.publicId(), ca.type(), ca.symbol(),
          "DIV_ACC", "Dividend: " + ca.symbol() + " $" + plain(ca.cashRate()) + "/share",
          "$" + amount.toPlainString() + " on your " + plain(h.qty()) + " shares is payable on " + ca.payableDate()
              + "; it counts in your equity until then.", now));
    }
    dividends.addDividendEx(ca.symbol(), ca.exDate(), ca.cashRate(), now);
    log.info("Dividend {} {} ${}/share accrued for {} account(s), payable {}", ca.publicId(), ca.symbol(),
        plain(ca.cashRate()), n, ca.payableDate());
  }

  // ------------------------------------------------------------------------------------------- helpers

  private CorporateActionRow applied(CorporateActionRow ca, CorporateActionStatus expected, String detailOrNull,
                                     Long resolvedBy, Instant now, EngineTx tx) {
    if (!store.transition(ca.id(), expected, CorporateActionStatus.APPLIED,
        detailOrNull != null ? detailOrNull : ca.detail(), now, resolvedBy)) {
      throw notResolvable(ca);                     // changed under us: roll the whole application back
    }
    if (ca.isSplit()) {
      // a split is applied once per (symbol, ex-date): any other report of it (provider or SplitGuard) is moot now
      for (CorporateActionRow o : store.splitsOn(ca.symbol(), ca.exDate())) {
        if (o.id() != ca.id() && (o.status() == CorporateActionStatus.PENDING
            || o.status() == CorporateActionStatus.NEEDS_ADMIN)) {
          store.transition(o.id(), o.status(), CorporateActionStatus.SKIPPED,
              CorporateActionStore.appendDetail(o.detail(), "superseded by " + ca.publicId()), null, null);
        }
      }
    }
    tx.publish(new CorporateActionApplied(ca.publicId(), ca.symbol(), ca.type(), now));
    return store.byId(ca.id()).orElseThrow();
  }

  private CorporateActionRow needsAdmin(CorporateActionRow ca, String why, Instant now, EngineTx tx) {
    store.transition(ca.id(), CorporateActionStatus.PENDING, CorporateActionStatus.NEEDS_ADMIN,
        CorporateActionStore.appendDetail(ca.detail(), why), null, null);
    tx.publish(new AdminAlertRaised("WARN", "Corporate action needs review: " + ca.symbol() + " " + ca.type(),
        ca.publicId() + " (ex-date " + ca.exDate() + "): " + why, now));
    log.warn("Corporate action {} {} {} needs the admin: {}", ca.publicId(), ca.symbol(), ca.type(), why);
    return store.byId(ca.id()).orElseThrow();
  }

  private CorporateActionRow supersede(CorporateActionRow ca, CorporateActionRow applied, Instant now) {
    store.transition(ca.id(), CorporateActionStatus.PENDING, CorporateActionStatus.SKIPPED,
        CorporateActionStore.appendDetail(ca.detail(), "superseded by " + applied.publicId()), null, null);
    return store.byId(ca.id()).orElseThrow();
  }

  private CorporateActionRow find(String actionId) {
    return store.byPublicId(actionId)
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No corporate action " + actionId + "."));
  }

  private static ApiException notResolvable(CorporateActionRow ca) {
    return ApiException.of(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE,
        "Corporate action " + ca.publicId() + " changed while it was being resolved; reload and retry.");
  }

  /** ACTIVE accounts holding {@code symbol} now (the position table). */
  List<Holder> holders(String symbol) {
    return jdbc.sql("""
            SELECT p.account_id, a.player_id, p.qty FROM position p JOIN account a ON a.id = p.account_id
             WHERE p.symbol = ? AND a.status = 'ACTIVE' ORDER BY p.account_id""")
        .param(symbol).query((rs, n) -> new Holder(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3))).list();
  }

  /**
   * ACTIVE accounts holding {@code symbol} at {@code t}, by forward replay (§16.4): executions before {@code t} plus
   * position adjustments before {@code t}, renames included. Used for a dividend applied after its ex-date.
   */
  List<Holder> holdersAt(String symbol, Instant t) {
    return jdbc.sql("""
            SELECT x.account_id, a.player_id, SUM(x.q) AS qty FROM (
                SELECT account_id, CASE WHEN side = 'BUY' THEN qty ELSE -qty END AS q
                  FROM execution WHERE symbol = ? AND executed_at < ?
                UNION ALL
                SELECT account_id, qty_after AS q FROM position_adjustment WHERE symbol_after = ? AND effective_at < ?
                UNION ALL
                SELECT account_id, -qty_before AS q FROM position_adjustment
                 WHERE symbol_before = ? AND effective_at < ?) x
              JOIN account a ON a.id = x.account_id
             WHERE a.status = 'ACTIVE'
             GROUP BY x.account_id, a.player_id
            HAVING SUM(x.q) > 0
             ORDER BY x.account_id""")
        .params(symbol, Jdbc.ts(t), symbol, Jdbc.ts(t), symbol, Jdbc.ts(t))
        .query((rs, n) -> new Holder(rs.getLong(1), rs.getLong(2), Qty.normalize(rs.getBigDecimal(3)))).list();
  }

  /** I5 for one position, right after a corporate action changed it (§14 step 5). */
  private void assertI5(long accountId, String symbol) {
    BigDecimal[] lots = jdbc.sql("""
            SELECT COALESCE(SUM(qty_remaining), 0), COALESCE(SUM(cost_remaining), 0) FROM position_lot
             WHERE account_id = ? AND symbol = ? AND closed_at IS NULL""")
        .params(accountId, symbol)
        .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}).single();
    Optional<PositionRow> p = accounts.position(accountId, symbol);
    BigDecimal qty = p.map(PositionRow::qty).orElse(BigDecimal.ZERO);
    BigDecimal cost = p.map(PositionRow::costBasis).orElse(BigDecimal.ZERO);
    if (qty.compareTo(lots[0]) != 0 || cost.compareTo(lots[1]) != 0) {
      throw new IllegalStateException("I5 violated by a corporate action for account " + accountId + " " + symbol
          + ": position " + qty + "/" + cost + " vs lots " + lots[0] + "/" + lots[1]);
    }
  }

  /** The pre-open of the ex-date: positions before it are the entitled ones (T+1: ex-date = record date). */
  Instant exCutoff(LocalDate exDate) {
    return ExDates.cutoff(exDate, clock.calendar());
  }

  private LocalDate tradingDateOnOrAfter(LocalDate date) {
    MarketCalendar cal = clock.calendar();
    try {
      return cal.isTradingDay(date) ? date : cal.nextTradingDay(date);
    } catch (RuntimeException e) {
      return date;
    }
  }

  static boolean validRates(BigDecimal oldRate, BigDecimal newRate) {
    return oldRate != null && newRate != null && oldRate.signum() > 0 && newRate.signum() > 0
        && oldRate.compareTo(newRate) != 0;
  }

  static String normalize(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    return symbol.trim().toUpperCase(Locale.ROOT);
  }

  static String plain(BigDecimal v) {
    return v == null ? "?" : v.stripTrailingZeros().toPlainString();
  }

  private static String clip(String memo) {
    return memo.length() <= 200 ? memo : memo.substring(0, 200);
  }
}
