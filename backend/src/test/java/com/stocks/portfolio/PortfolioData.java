package com.stocks.portfolio;

import com.stocks.common.domain.TransferType;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.portfolio.UnitFlow;
import com.stocks.contract.portfolio.UnitMath;
import com.stocks.contract.portfolio.Valuation;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.AccountRef;
import com.stocks.testkit.Fixtures.PlayerRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test data for WS4 on top of the WS1 {@link Fixtures}, written straight to WS3's tables the way the engine would
 * (§12.8 posting, §16.2 unit accounting, §21.2 transfers): buys and sells with realized P&amp;L and channel, contribution
 * flows at the pre-flow unit value (rebase included), P&amp;L corrections, resets that continue the unit series, and
 * dividends. No fees. Uses the real {@link ValuationService} for E⁻, exactly like {@code ApplyCashTransfersCmd}.
 */
public final class PortfolioData {

  public final Fixtures fx;
  private final JdbcClient jdbc;
  private final MutableTimeSource time;
  private final PublicId ids;
  private final ValuationService valuation;

  public PortfolioData(JdbcClient jdbc, MutableTimeSource time, ValuationService valuation) {
    this.jdbc = jdbc;
    this.time = time;
    this.valuation = valuation;
    this.ids = new PublicId(time);
    this.fx = new Fixtures(jdbc, time);
  }

  // ------------------------------------------------------------------------------------------- players & runs

  public PlayerRef human(String handle) {
    return fx.human(handle);
  }

  public PlayerRef ai(String handle, PlayerRef owner) {
    return fx.ai(handle, owner);
  }

  /** Run 1 funded with an INITIAL transfer at U = 1. */
  public AccountRef open(PlayerRef player, String startingCash) {
    return fx.account(player, startingCash);
  }

  // ------------------------------------------------------------------------------------------- flows

  /** A contribution flow at the pre-flow U (§16.2), E⁻ valued live. */
  public UnitFlow contribution(AccountRef account, TransferType type, String amount, String memo) {
    BigDecimal f = new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
    Valuation before = valuation.valueNow(account.id());
    BigDecimal lastU = jdbc.sql("SELECT unit_value FROM cash_transfer WHERE account_id = ? AND status = 'APPLIED' "
            + "AND unit_value IS NOT NULL ORDER BY effective_at DESC, id DESC LIMIT 1")
        .param(account.id()).query(BigDecimal.class).optional().orElse(BigDecimal.ONE);
    UnitFlow flow = UnitMath.contribution(f, before.equity(), before.units(), before.unitSeries(), lastU);
    insertTransfer(account, type, f, true, memo, flow.unitValue(), flow.unitsDelta(), flow.unitSeriesAfter(),
        flow.rebased(), before.equity());
    jdbc.sql("UPDATE account SET net_contributions = net_contributions + ?, units = ?, unit_series = ? WHERE id = ?")
        .params(f, flow.unitsAfter(), flow.unitSeriesAfter(), account.id()).update();
    return flow;
  }

  public UnitFlow deposit(AccountRef account, String amount) {
    return contribution(account, TransferType.DEPOSIT, amount, "Admin deposit");
  }

  public UnitFlow withdraw(AccountRef account, String amount) {
    return contribution(account, TransferType.WITHDRAWAL, "-" + amount, "Admin withdrawal");
  }

  /** An ADJUSTMENT treated as PNL_CORRECTION: performance, {@code units_delta = 0} (§16.2). */
  public void pnlCorrection(AccountRef account, String signedAmount) {
    BigDecimal f = new BigDecimal(signedAmount).setScale(2, RoundingMode.UNNECESSARY);
    insertTransfer(account, TransferType.ADJUSTMENT, f, false, "Game bug fix", null, BigDecimal.ZERO,
        currentSeries(account), false, null);
  }

  private int currentSeries(AccountRef account) {
    return jdbc.sql("SELECT unit_series FROM account WHERE id = ?").param(account.id()).query(Integer.class).single();
  }

  private void insertTransfer(AccountRef account, TransferType type, BigDecimal amount, boolean contribution,
                              String memo, BigDecimal unitValue, BigDecimal unitsDelta, int series, boolean rebased,
                              BigDecimal equityBefore) {
    Instant now = time.now();
    LocalDate today = time.todayEt();
    String batch = ids.next(Prefix.TRANSFER_BATCH);
    jdbc.sql("INSERT INTO transfer_batch (public_id, scope, transfer_type, amount, memo, applied_count, created_at) "
            + "VALUES (?, 'SINGLE', ?, ?, ?, 1, ?)")
        .params(batch, type.name(), amount.abs(), memo, Jdbc.ts(now)).update();
    long batchId = jdbc.sql("SELECT id FROM transfer_batch WHERE public_id = ?").param(batch).query(Long.class)
        .single();
    String xfr = ids.next(Prefix.CASH_TRANSFER);
    jdbc.sql("""
            INSERT INTO cash_transfer (public_id, batch_id, account_id, transfer_type, amount, is_contribution, status,
                                       memo, equity_before, unit_value, units_delta, unit_series, rebased, effective_at)
            VALUES (?, ?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?, ?, ?, ?)""")
        .params(xfr, batchId, account.id(), type.name(), amount, contribution, memo, Jdbc.decimalParam(equityBefore),
            Jdbc.decimalParam(unitValue), unitsDelta, series, rebased, Jdbc.ts(now))
        .update();
    long xfrId = jdbc.sql("SELECT id FROM cash_transfer WHERE public_id = ?").param(xfr).query(Long.class).single();
    BigDecimal balance = cash(account).add(amount);
    String entry = switch (type) {
      case INITIAL -> "INITIAL_FUNDING";
      default -> type.name();
    };
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, transfer_id, memo, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(ids.next(Prefix.LEDGER), account.id(), entry, amount, balance, today, today, Jdbc.ts(now), xfrId, memo,
            "XFER:" + xfrId)
        .update();
    jdbc.sql("UPDATE account SET cash_balance = ? WHERE id = ?").params(balance, account.id()).update();
  }

  public BigDecimal cash(AccountRef account) {
    return fx.cash(account);
  }

  // ------------------------------------------------------------------------------------------- trades

  /** A filled BUY at {@code price} now (no fees); returns the execution's public id. */
  public String buy(AccountRef account, String symbol, String qty, String price) {
    return trade(account, "BUY", symbol, qty, price, "WEB", null, false);
  }

  /** A filled SELL at {@code price} now (no fees), relieving cost pro rata; returns the execution's public id. */
  public String sell(AccountRef account, String symbol, String qty, String price) {
    return trade(account, "SELL", symbol, qty, price, "WEB", null, false);
  }

  /** A fill with a channel and an optional rationale ({@code rationale_public} as given). */
  public String trade(AccountRef account, String side, String symbol, String qty, String price, String channel,
                      String rationale, boolean rationalePublic) {
    fx.asset(symbol);
    BigDecimal q = new BigDecimal(qty);
    BigDecimal p = new BigDecimal(price);
    BigDecimal principal = Rounding.PRINCIPAL.multiply(q, p);
    boolean buy = "BUY".equals(side);
    Instant now = time.now();
    LocalDate today = time.todayEt();
    BigDecimal realized = null;
    if (!buy) {
      BigDecimal[] pos = position(account, symbol);
      BigDecimal cost = pos[0].compareTo(q) == 0 ? pos[1]
          : pos[1].multiply(q).divide(pos[0], 2, RoundingMode.HALF_EVEN);
      realized = principal.subtract(cost);
      if (pos[0].compareTo(q) == 0) {
        jdbc.sql("DELETE FROM position WHERE account_id = ? AND symbol = ?").params(account.id(), symbol).update();
      } else {
        jdbc.sql("UPDATE position SET qty = qty - ?, cost_basis = cost_basis - ?, updated_at = ? "
                + "WHERE account_id = ? AND symbol = ?")
            .params(q, cost, Jdbc.ts(now), account.id(), symbol).update();
      }
    }
    String ord = ids.next(Prefix.ORDER);
    jdbc.sql("""
            INSERT INTO trade_order (public_id, account_id, player_id, client_order_id, request_hash, symbol, side,
                                     order_type, qty, fractional, time_in_force, extended_hours, status, filled_qty,
                                     filled_principal, avg_fill_price, session_date, received_at, accepted_at,
                                     eligible_at, last_fill_at, expires_at, done_at, channel, rationale,
                                     rationale_public)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'MARKET', ?, ?, 'DAY', FALSE, 'FILLED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(ord, account.id(), account.playerId(), "t-" + UUID.randomUUID(), "0".repeat(64), symbol, side, q,
            q.stripTrailingZeros().scale() > 0, q, principal, p, today, Jdbc.ts(now), Jdbc.ts(now), Jdbc.ts(now),
            Jdbc.ts(now), Jdbc.ts(now), Jdbc.ts(now), channel, rationale, rationalePublic)
        .update();
    long orderId = jdbc.sql("SELECT id FROM trade_order WHERE public_id = ?").param(ord).query(Long.class).single();
    String exe = ids.next(Prefix.EXECUTION);
    BigDecimal net = buy ? principal.negate() : principal;
    jdbc.sql("""
            INSERT INTO execution (public_id, order_id, account_id, symbol, side, qty, price, principal, net_amount,
                                   realized_pnl, liquidity, executed_at, trade_date, settle_date, ref_source,
                                   ref_quality)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TOUCH', ?, ?, ?, 'test', 'SIMULATED')""")
        .params(exe, orderId, account.id(), symbol, side, q, p, principal, net, Jdbc.decimalParam(realized),
            Jdbc.ts(now), today, today.plusDays(1))
        .update();
    long exeId = jdbc.sql("SELECT id FROM execution WHERE public_id = ?").param(exe).query(Long.class).single();
    BigDecimal balance = cash(account).add(net);
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, execution_id, symbol, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(ids.next(Prefix.LEDGER), account.id(), buy ? "TRADE_BUY" : "TRADE_SELL", net, balance, today,
            today.plusDays(1), Jdbc.ts(now), exeId, symbol, "EXEC:" + exeId)
        .update();
    jdbc.sql("UPDATE account SET cash_balance = ? WHERE id = ?").params(balance, account.id()).update();
    if (buy) {
      long existing = jdbc.sql("SELECT COUNT(*) FROM position WHERE account_id = ? AND symbol = ?")
          .params(account.id(), symbol).query(Long.class).single();
      if (existing == 0) {
        jdbc.sql("INSERT INTO position (account_id, symbol, qty, cost_basis, opened_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)")
            .params(account.id(), symbol, q, principal, Jdbc.ts(now), Jdbc.ts(now)).update();
      } else {
        jdbc.sql("UPDATE position SET qty = qty + ?, cost_basis = cost_basis + ?, updated_at = ? "
                + "WHERE account_id = ? AND symbol = ?")
            .params(q, principal, Jdbc.ts(now), account.id(), symbol).update();
      }
    }
    return exe;
  }

  private BigDecimal[] position(AccountRef account, String symbol) {
    return jdbc.sql("SELECT qty, cost_basis FROM position WHERE account_id = ? AND symbol = ?")
        .params(account.id(), symbol)
        .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("qty"), rs.getBigDecimal("cost_basis")})
        .single();
  }

  /** A 1:1 NAME_CHANGE of a held position, recorded as {@code position_adjustment} rows (§14). */
  public void rename(AccountRef account, String from, String to) {
    fx.asset(to);
    Instant now = time.now();
    String cac = ids.next(Prefix.CORPORATE_ACTION);
    jdbc.sql("""
            INSERT INTO corporate_action (public_id, symbol, action_type, ex_date, new_symbol, status, origin,
                                          created_at, applied_at)
            VALUES (?, ?, 'NAME_CHANGE', ?, ?, 'APPLIED', 'MANUAL', ?, ?)""")
        .params(cac, from, time.todayEt(), to, Jdbc.ts(now), Jdbc.ts(now)).update();
    long caId = jdbc.sql("SELECT id FROM corporate_action WHERE public_id = ?").param(cac).query(Long.class).single();
    BigDecimal[] pos = position(account, from);
    long exeId = jdbc.sql("SELECT MIN(id) FROM execution WHERE account_id = ? AND symbol = ?")
        .params(account.id(), from).query(Long.class).single();
    jdbc.sql("""
            INSERT INTO position_lot (account_id, symbol, open_execution_id, trade_date, opened_at, qty_original,
                                      qty_remaining, cost_remaining)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(account.id(), to, exeId, time.todayEt(), Jdbc.ts(now), pos[0], pos[0], pos[1]).update();
    long lotId = jdbc.sql("SELECT MAX(id) FROM position_lot WHERE account_id = ?").param(account.id())
        .query(Long.class).single();
    jdbc.sql("""
            INSERT INTO position_adjustment (account_id, corporate_action_id, lot_id, symbol_before, symbol_after,
                                             qty_before, qty_after, cost_before, cost_after, effective_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(account.id(), caId, lotId, from, to, pos[0], pos[0], pos[1], pos[1], Jdbc.ts(now)).update();
    jdbc.sql("UPDATE position SET symbol = ? WHERE account_id = ? AND symbol = ?").params(to, account.id(), from)
        .update();
  }

  // ------------------------------------------------------------------------------------------- reset

  /**
   * Closes the run at its live value and opens run n+1 at the final U (§16.3), funded with {@code startingCash}.
   * Positions must be sold first.
   */
  public AccountRef reset(AccountRef account, String startingCash) {
    Valuation v = valuation.valueNow(account.id());
    Instant now = time.now();
    jdbc.sql("""
            UPDATE account SET status = 'CLOSED', closed_at = ?, close_reason = 'RESET', final_equity = ?,
                               final_unit_value = ? WHERE id = ?""")
        .params(Jdbc.ts(now), v.equity(), v.unitValue(), account.id()).update();
    int runNo = jdbc.sql("SELECT run_no FROM account WHERE id = ?").param(account.id()).query(Integer.class).single();
    BigDecimal cash = new BigDecimal(startingCash).setScale(2, RoundingMode.UNNECESSARY);
    UnitFlow open = UnitMath.openRun(cash, v.unitValue(), v.unitSeries());
    String pid = ids.next(Prefix.ACCOUNT);
    jdbc.sql("""
            INSERT INTO account (public_id, player_id, run_no, status, starting_cash, opened_at, unit_series)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)""")
        .params(pid, account.playerId(), runNo + 1, cash, Jdbc.ts(now), open.unitSeriesAfter()).update();
    long id = jdbc.sql("SELECT id FROM account WHERE public_id = ?").param(pid).query(Long.class).single();
    AccountRef next = new AccountRef(id, pid, account.playerId());
    insertTransfer(next, TransferType.INITIAL, cash, true, "Reset", open.unitValue(), open.unitsDelta(),
        open.unitSeriesAfter(), open.rebased(), null);
    jdbc.sql("UPDATE account SET net_contributions = ?, units = ? WHERE id = ?")
        .params(cash, open.unitsAfter(), id).update();
    return next;
  }

  // ------------------------------------------------------------------------------------------- dividends

  /** Accrues a cash dividend on the account's position now (ex-date); returns the entitlement id. */
  public long accrueDividend(AccountRef account, String symbol, String amount, LocalDate payable) {
    Instant now = time.now();
    String cac = ids.next(Prefix.CORPORATE_ACTION);
    jdbc.sql("""
            INSERT INTO corporate_action (public_id, symbol, action_type, ex_date, payable_date, cash_rate, status,
                                          origin, created_at, applied_at)
            VALUES (?, ?, 'CASH_DIVIDEND', ?, ?, 0.5, 'APPLIED', 'MANUAL', ?, ?)""")
        .params(cac, symbol, time.todayEt(), payable, Jdbc.ts(now), Jdbc.ts(now)).update();
    long caId = jdbc.sql("SELECT id FROM corporate_action WHERE public_id = ?").param(cac).query(Long.class).single();
    BigDecimal qty = position(account, symbol)[0];
    jdbc.sql("""
            INSERT INTO dividend_entitlement (corporate_action_id, account_id, symbol, eligible_qty, amount,
                                              payable_date, status, accrued_at)
            VALUES (?, ?, ?, ?, ?, ?, 'ACCRUED', ?)""")
        .params(caId, account.id(), symbol, qty, new BigDecimal(amount), payable, Jdbc.ts(now)).update();
    return jdbc.sql("SELECT id FROM dividend_entitlement WHERE corporate_action_id = ?").param(caId)
        .query(Long.class).single();
  }

  /** Pays an accrued dividend now: a DIVIDEND ledger row, {@code paid_at} = its {@code posted_at}. */
  public void payDividend(AccountRef account, long entitlementId) {
    Instant now = time.now();
    BigDecimal amount = jdbc.sql("SELECT amount FROM dividend_entitlement WHERE id = ?").param(entitlementId)
        .query(BigDecimal.class).single();
    BigDecimal balance = cash(account).add(amount);
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, dividend_id, idempotency_key)
            VALUES (?, ?, 'DIVIDEND', ?, ?, ?, ?, ?, ?, ?)""")
        .params(ids.next(Prefix.LEDGER), account.id(), amount, balance, time.todayEt(), time.todayEt(), Jdbc.ts(now),
            entitlementId, "DIV:" + entitlementId)
        .update();
    jdbc.sql("UPDATE account SET cash_balance = ? WHERE id = ?").params(balance, account.id()).update();
    jdbc.sql("UPDATE dividend_entitlement SET status = 'PAID', paid_at = ? WHERE id = ?")
        .params(Jdbc.ts(now), entitlementId).update();
  }

  public static BigDecimal money(String v) {
    return Money.cents(new BigDecimal(v));
  }
}
