package com.stocks.trading.store;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Money;
import com.stocks.trading.account.BalanceCalculator.LedgerSums;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read access to accounts, the ledger, positions, lots and the player columns trading needs (§8.1). Reads go through
 * the caller's transaction when there is one.
 */
@Component
public class AccountStore {

  private static final String SELECT_ACCOUNT = """
      SELECT id, public_id, player_id, run_no, status, restriction, restriction_until, restriction_reason,
             cash_balance, net_contributions, units, unit_series, starting_cash, opened_at, closed_at, close_reason,
             final_equity, final_unit_value
        FROM account
      """;

  private static final String SELECT_PLAYER =
      "SELECT id, public_id, handle, display_name, kind, role, status FROM player";

  private final JdbcClient jdbc;

  public AccountStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- accounts

  public Optional<AccountRow> byId(long id) {
    return jdbc.sql(SELECT_ACCOUNT + " WHERE id = ?").param(id).query(AccountStore::account).optional();
  }

  public Optional<AccountRow> byPublicId(String publicId) {
    return jdbc.sql(SELECT_ACCOUNT + " WHERE public_id = ?").param(publicId).query(AccountStore::account).optional();
  }

  public Optional<AccountRow> activeByPlayer(long playerId) {
    return jdbc.sql(SELECT_ACCOUNT + " WHERE player_id = ? AND status = 'ACTIVE' ORDER BY run_no DESC")
        .param(playerId).query(AccountStore::account).list().stream().findFirst();
  }

  /** Every run of a player, newest first. */
  public List<AccountRow> runs(long playerId) {
    return jdbc.sql(SELECT_ACCOUNT + " WHERE player_id = ? ORDER BY run_no DESC").param(playerId)
        .query(AccountStore::account).list();
  }

  public int maxRunNo(long playerId) {
    return jdbc.sql("SELECT COALESCE(MAX(run_no), 0) FROM account WHERE player_id = ?").param(playerId)
        .query(Integer.class).single();
  }

  /** Accounts that already used a reset {@code Idempotency-Key} (§17.6 backstop). */
  public boolean resetKeyUsed(String scopedKey) {
    return jdbc.sql("SELECT COUNT(*) FROM account WHERE reset_idempotency_key = ?").param(scopedKey)
        .query(Integer.class).single() > 0;
  }

  // ------------------------------------------------------------------------------------------- ledger

  /** §10.2 sums as of an ET date. */
  public LedgerSums ledgerSums(long accountId, LocalDate asOfEt) {
    return jdbc.sql("""
            SELECT COALESCE(SUM(amount), 0) AS cash_total,
                   COALESCE(SUM(CASE WHEN settle_date <= ? THEN amount ELSE 0 END), 0) AS settled,
                   COALESCE(SUM(CASE WHEN amount > 0 AND settle_date > ? THEN amount ELSE 0 END), 0) AS credits,
                   COALESCE(SUM(CASE WHEN amount < 0 AND settle_date > ? THEN -amount ELSE 0 END), 0) AS debits
              FROM cash_ledger WHERE account_id = ?""")
        .params(asOfEt, asOfEt, asOfEt, accountId)
        .query((rs, n) -> new LedgerSums(rs.getBigDecimal("cash_total"), rs.getBigDecimal("settled"),
            rs.getBigDecimal("credits"), rs.getBigDecimal("debits")))
        .single();
  }

  /** The latest settle date of unsettled positive ledger rows (GFV funding attribution, §10.4), if any. */
  public Optional<LocalDate> latestUnsettledCreditDate(long accountId, LocalDate asOfEt) {
    // an aggregate over no rows is one NULL row: map it to empty (single() rejects a null value)
    return jdbc.sql("""
            SELECT MAX(settle_date) FROM cash_ledger WHERE account_id = ? AND amount > 0 AND settle_date > ?""")
        .params(accountId, asOfEt).query((rs, n) -> Optional.ofNullable(Jdbc.date(rs, 1))).single();
  }

  // ------------------------------------------------------------------------------------------- positions and lots

  public List<PositionRow> positions(long accountId) {
    return jdbc.sql("SELECT account_id, symbol, qty, cost_basis, opened_at, updated_at FROM position"
            + " WHERE account_id = ? ORDER BY symbol")
        .param(accountId).query(AccountStore::position).list();
  }

  public Optional<PositionRow> position(long accountId, String symbol) {
    return jdbc.sql("SELECT account_id, symbol, qty, cost_basis, opened_at, updated_at FROM position"
            + " WHERE account_id = ? AND symbol = ?")
        .params(accountId, symbol).query(AccountStore::position).optional();
  }

  /** Open lots of a position, FIFO (oldest first). */
  public List<LotRow> openLots(long accountId, String symbol) {
    return jdbc.sql("""
            SELECT id, account_id, symbol, open_execution_id, trade_date, opened_at, qty_original, qty_remaining,
                   cost_remaining, unsettled_funding, funding_settle_date
              FROM position_lot
             WHERE account_id = ? AND symbol = ? AND closed_at IS NULL AND qty_remaining > 0
             ORDER BY opened_at, id""")
        .params(accountId, symbol)
        .query((rs, n) -> new LotRow(rs.getLong("id"), rs.getLong("account_id"), rs.getString("symbol"),
            rs.getLong("open_execution_id"), Jdbc.date(rs, "trade_date"), Jdbc.instant(rs, "opened_at"),
            rs.getBigDecimal("qty_original"), rs.getBigDecimal("qty_remaining"), rs.getBigDecimal("cost_remaining"),
            rs.getBigDecimal("unsettled_funding"), Jdbc.date(rs, "funding_settle_date")))
        .list();
  }

  /** Σ ACCRUED dividend entitlements (the receivable of §16.1 at "now"). */
  public BigDecimal dividendsReceivable(long accountId) {
    return jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM dividend_entitlement WHERE account_id = ?"
            + " AND status = 'ACCRUED'")
        .param(accountId).query(BigDecimal.class).single().setScale(Money.SCALE);
  }

  // ------------------------------------------------------------------------------------------- transfers

  /** {@code unit_value} of the account's last APPLIED transfer (U while units are 0, §16.2), if any. */
  public Optional<BigDecimal> lastTransferUnitValue(long accountId) {
    return jdbc.sql("""
            SELECT unit_value FROM cash_transfer
             WHERE account_id = ? AND status = 'APPLIED' AND unit_value IS NOT NULL
             ORDER BY effective_at DESC, id DESC LIMIT 1""")
        .param(accountId).query(BigDecimal.class).optional();
  }

  /** {@code unit_value} of the run's INITIAL transfer (U at the run's opening, §16.3). */
  public Optional<BigDecimal> openingUnitValue(long accountId) {
    return jdbc.sql("SELECT unit_value FROM cash_transfer WHERE account_id = ? AND transfer_type = 'INITIAL'"
            + " ORDER BY id LIMIT 1")
        .param(accountId).query(BigDecimal.class).optional();
  }

  // ------------------------------------------------------------------------------------------- violations

  public int activeViolations(long accountId, Instant now) {
    return jdbc.sql("SELECT COUNT(*) FROM account_violation WHERE account_id = ? AND expires_at > ?")
        .params(accountId, Jdbc.ts(now)).query(Integer.class).single();
  }

  public Optional<Instant> nextViolationExpiry(long accountId, Instant now) {
    return jdbc.sql("SELECT MIN(expires_at) FROM account_violation WHERE account_id = ? AND expires_at > ?")
        .params(accountId, Jdbc.ts(now)).query((rs, n) -> Optional.ofNullable(Jdbc.instant(rs, 1))).single();
  }

  // ------------------------------------------------------------------------------------------- players

  public Optional<PlayerRow> player(long playerId) {
    return jdbc.sql(SELECT_PLAYER + " WHERE id = ?").param(playerId).query(AccountStore::player).optional();
  }

  public Optional<PlayerRow> playerByHandle(String handle) {
    return jdbc.sql(SELECT_PLAYER + " WHERE handle = ?").param(handle).query(AccountStore::player).optional();
  }

  /**
   * Targets of a transfer to ALL (§21.2): players with status ACTIVE or FROZEN, of the given kinds, that have an
   * ACTIVE account, in handle order.
   */
  public List<PlayerRow> transferTargets(Set<PlayerKind> kinds) {
    String in = kinds.stream().map(k -> "'" + k.name() + "'").sorted().collect(Collectors.joining(","));
    return jdbc.sql(SELECT_PLAYER + " p WHERE p.status IN ('ACTIVE','FROZEN') AND p.kind IN (" + in + ")"
            + " AND EXISTS (SELECT 1 FROM account a WHERE a.player_id = p.id AND a.status = 'ACTIVE')"
            + " ORDER BY p.handle")
        .query(AccountStore::player).list();
  }

  // ------------------------------------------------------------------------------------------- mapping

  static AccountRow account(ResultSet rs, int n) throws SQLException {
    return new AccountRow(rs.getLong("id"), rs.getString("public_id"), rs.getLong("player_id"), rs.getInt("run_no"),
        AccountStatus.valueOf(rs.getString("status")), AccountRestriction.valueOf(rs.getString("restriction")),
        Jdbc.instant(rs, "restriction_until"), rs.getString("restriction_reason"), rs.getBigDecimal("cash_balance"),
        rs.getBigDecimal("net_contributions"), rs.getBigDecimal("units"), rs.getInt("unit_series"),
        rs.getBigDecimal("starting_cash"), Jdbc.instant(rs, "opened_at"), Jdbc.instant(rs, "closed_at"),
        rs.getString("close_reason"), rs.getBigDecimal("final_equity"), rs.getBigDecimal("final_unit_value"));
  }

  static PositionRow position(ResultSet rs, int n) throws SQLException {
    return new PositionRow(rs.getLong("account_id"), rs.getString("symbol"), rs.getBigDecimal("qty"),
        rs.getBigDecimal("cost_basis"), Jdbc.instant(rs, "opened_at"), Jdbc.instant(rs, "updated_at"));
  }

  static PlayerRow player(ResultSet rs, int n) throws SQLException {
    return new PlayerRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("handle"),
        rs.getString("display_name"), PlayerKind.valueOf(rs.getString("kind")),
        PlayerRole.valueOf(rs.getString("role")), PlayerStatus.valueOf(rs.getString("status")));
  }
}
