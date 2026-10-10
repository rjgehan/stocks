package com.stocks.portfolio;

import com.stocks.common.domain.TransferType;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to WS3's money tables ({@code account}, {@code position}, {@code cash_ledger},
 * {@code cash_transfer}, {@code execution}, {@code dividend_entitlement}) for valuation and performance (§16).
 * {@link JdbcClient} is transaction-aware, so a call made inside an engine command sees that command's uncommitted
 * rows (§5.3 {@code ValuationService.valueNow}). Instants cross JDBC only through {@link Jdbc} (§8).
 */
@Repository
public class PortfolioRepository {

  /** One run of a player ({@code account} row). Money at scale 2, units and unit values at scale 18. */
  public record AccountRow(long id, String publicId, long playerId, int runNo, String status, BigDecimal cash,
                           BigDecimal netContributions, BigDecimal units, int unitSeries, BigDecimal startingCash,
                           Instant openedAt, Instant closedAt, BigDecimal finalEquity, BigDecimal finalUnitValue) {

    public boolean active() {
      return "ACTIVE".equals(status);
    }

    /** True if this run was open at {@code t}: opened at or before it and not closed by then. */
    public boolean openAt(Instant t) {
      return !openedAt.isAfter(t) && (closedAt == null || closedAt.isAfter(t));
    }
  }

  /** A current position ({@code position} row). */
  public record PositionRow(long accountId, String symbol, BigDecimal qty, BigDecimal costBasis, Instant openedAt) {}

  /** An APPLIED {@code cash_transfer} row. */
  public record TransferRow(long id, String publicId, long accountId, TransferType type, BigDecimal amount,
                            boolean contribution, BigDecimal unitValue, BigDecimal unitsDelta, int unitSeries,
                            boolean rebased, String memo, Instant effectiveAt, BigDecimal spyMark) {}

  private static final String ACCOUNT_COLUMNS = """
      a.id, a.public_id, a.player_id, a.run_no, a.status, a.cash_balance, a.net_contributions, a.units,
      a.unit_series, a.starting_cash, a.opened_at, a.closed_at, a.final_equity, a.final_unit_value""";

  private static final String TRANSFER_COLUMNS = """
      t.id, t.public_id, t.account_id, t.transfer_type, t.amount, t.is_contribution, t.unit_value, t.units_delta,
      t.unit_series, t.rebased, t.memo, t.effective_at, t.spy_mark""";

  private final JdbcClient jdbc;

  public PortfolioRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- accounts

  public Optional<AccountRow> account(long accountId) {
    return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account a WHERE a.id = ?")
        .param(accountId).query(PortfolioRepository::account).optional();
  }

  /** Every ACTIVE account (one per player), by id. */
  public List<AccountRow> activeAccounts() {
    return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account a WHERE a.status = 'ACTIVE' ORDER BY a.id")
        .query(PortfolioRepository::account).list();
  }

  /** Every run of a player, oldest first. */
  public List<AccountRow> runsOfPlayer(long playerId) {
    return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account a WHERE a.player_id = ? ORDER BY a.run_no")
        .param(playerId).query(PortfolioRepository::account).list();
  }

  /** Every run of every player, keyed by player id (runs oldest first). */
  public Map<Long, List<AccountRow>> runsByPlayer() {
    Map<Long, List<AccountRow>> out = new LinkedHashMap<>();
    jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account a ORDER BY a.player_id, a.run_no")
        .query(PortfolioRepository::account).list()
        .forEach(a -> out.computeIfAbsent(a.playerId(), k -> new ArrayList<>()).add(a));
    return out;
  }

  /** Runs that were open at {@code t} (opened at or before it, not closed by then). */
  public List<AccountRow> accountsOpenAt(Instant t) {
    return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account a WHERE a.opened_at <= ? "
            + "AND (a.closed_at IS NULL OR a.closed_at > ?) ORDER BY a.id")
        .params(Jdbc.ts(t), Jdbc.ts(t)).query(PortfolioRepository::account).list();
  }

  public Optional<Long> activeAccountId(long playerId) {
    return jdbc.sql("SELECT id FROM account WHERE player_id = ? AND status = 'ACTIVE'")
        .param(playerId).query(Long.class).optional();
  }

  private static AccountRow account(ResultSet rs, int n) throws SQLException {
    return new AccountRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"), rs.getInt("run_no"),
        rs.getString("status"), rs.getBigDecimal("cash_balance"), rs.getBigDecimal("net_contributions"),
        rs.getBigDecimal("units"), rs.getInt("unit_series"), rs.getBigDecimal("starting_cash"),
        Jdbc.instant(rs, "opened_at"), Jdbc.instant(rs, "closed_at"), rs.getBigDecimal("final_equity"),
        rs.getBigDecimal("final_unit_value"));
  }

  // ------------------------------------------------------------------------------------------- positions

  public List<PositionRow> positions(long accountId) {
    return jdbc.sql("SELECT account_id, symbol, qty, cost_basis, opened_at FROM position WHERE account_id = ? "
            + "ORDER BY symbol")
        .param(accountId).query(PortfolioRepository::position).list();
  }

  /** Positions of every ACTIVE account, keyed by account id. */
  public Map<Long, List<PositionRow>> positionsOfActiveAccounts() {
    Map<Long, List<PositionRow>> out = new HashMap<>();
    jdbc.sql("""
            SELECT p.account_id, p.symbol, p.qty, p.cost_basis, p.opened_at
              FROM position p JOIN account a ON a.id = p.account_id
             WHERE a.status = 'ACTIVE'
             ORDER BY p.account_id, p.symbol""")
        .query(PortfolioRepository::position).list()
        .forEach(p -> out.computeIfAbsent(p.accountId(), k -> new ArrayList<>()).add(p));
    return out;
  }

  private static PositionRow position(ResultSet rs, int n) throws SQLException {
    return new PositionRow(rs.getLong("account_id"), rs.getString("symbol"), rs.getBigDecimal("qty"),
        rs.getBigDecimal("cost_basis"), Jdbc.instant(rs, "opened_at"));
  }

  // ------------------------------------------------------------------------------------------- cash & receivables

  /** §16.1 {@code cashTotal(t) = Σ cash_ledger.amount WHERE posted_at ≤ t}. */
  public BigDecimal cashAt(long accountId, Instant t) {
    BigDecimal v = jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM cash_ledger WHERE account_id = ? "
            + "AND posted_at <= ?")
        .params(accountId, Jdbc.ts(t)).query(BigDecimal.class).single();
    return money(v);
  }

  /**
   * §16.1 point-in-time dividend receivable: accrued at or before {@code t}, and neither paid nor voided by then (once
   * paid, the cash counts instead).
   */
  public BigDecimal receivableAt(long accountId, Instant t) {
    BigDecimal v = jdbc.sql("""
            SELECT COALESCE(SUM(amount), 0) FROM dividend_entitlement
             WHERE account_id = ? AND accrued_at <= ?
               AND (paid_at IS NULL OR paid_at > ?) AND (voided_at IS NULL OR voided_at > ?)""")
        .params(accountId, Jdbc.ts(t), Jdbc.ts(t), Jdbc.ts(t)).query(BigDecimal.class).single();
    return money(v);
  }

  /** {@link #receivableAt} for every ACTIVE account that has one. */
  public Map<Long, BigDecimal> receivablesOfActiveAccounts(Instant t) {
    Map<Long, BigDecimal> out = new HashMap<>();
    jdbc.sql("""
            SELECT d.account_id, SUM(d.amount) AS amount
              FROM dividend_entitlement d JOIN account a ON a.id = d.account_id
             WHERE a.status = 'ACTIVE' AND d.accrued_at <= ?
               AND (d.paid_at IS NULL OR d.paid_at > ?) AND (d.voided_at IS NULL OR d.voided_at > ?)
             GROUP BY d.account_id""")
        .params(Jdbc.ts(t), Jdbc.ts(t), Jdbc.ts(t))
        .query(rs -> {
          out.put(rs.getLong("account_id"), money(rs.getBigDecimal("amount")));
        });
    return out;
  }

  // ------------------------------------------------------------------------------------------- transfers

  /** APPLIED transfers of one account, oldest first ({@code effective_at}, then id). */
  public List<TransferRow> appliedTransfers(long accountId) {
    return jdbc.sql("SELECT " + TRANSFER_COLUMNS + " FROM cash_transfer t WHERE t.account_id = ? "
            + "AND t.status = 'APPLIED' ORDER BY t.effective_at, t.id")
        .param(accountId).query(PortfolioRepository::transfer).list();
  }

  /** APPLIED transfers of every run of a player, oldest first. */
  public List<TransferRow> appliedTransfersOfPlayer(long playerId) {
    return jdbc.sql("SELECT " + TRANSFER_COLUMNS + " FROM cash_transfer t JOIN account a ON a.id = t.account_id "
            + "WHERE a.player_id = ? AND t.status = 'APPLIED' ORDER BY t.effective_at, t.id")
        .param(playerId).query(PortfolioRepository::transfer).list();
  }

  /** U of the account's last APPLIED transfer (§16.2: the unit value of an account with no units). */
  public Optional<BigDecimal> lastUnitValue(long accountId) {
    return jdbc.sql("""
            SELECT unit_value FROM cash_transfer
             WHERE account_id = ? AND status = 'APPLIED' AND unit_value IS NOT NULL
             ORDER BY effective_at DESC, id DESC LIMIT 1""")
        .param(accountId).query(BigDecimal.class).optional();
  }

  private static TransferRow transfer(ResultSet rs, int n) throws SQLException {
    return new TransferRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("account_id"),
        TransferType.valueOf(rs.getString("transfer_type")), rs.getBigDecimal("amount"),
        rs.getBoolean("is_contribution"), rs.getBigDecimal("unit_value"), rs.getBigDecimal("units_delta"),
        rs.getInt("unit_series"), rs.getBoolean("rebased"), rs.getString("memo"), Jdbc.instant(rs, "effective_at"),
        rs.getBigDecimal("spy_mark"));
  }

  // ------------------------------------------------------------------------------------------- prices

  /** The most recent execution price of {@code symbol} at or before {@code t}, in any account. */
  public Optional<BigDecimal> lastFillPrice(String symbol, Instant t) {
    return jdbc.sql("""
            SELECT price FROM execution WHERE symbol = ? AND executed_at <= ?
             ORDER BY executed_at DESC, id DESC LIMIT 1""")
        .params(symbol, Jdbc.ts(t)).query(BigDecimal.class).optional();
  }

  /** A cash amount read from SQL (a SUM may be null), at scale 2. */
  static BigDecimal money(BigDecimal v) {
    return v == null ? Money.ZERO : Money.cents(v);
  }
}
