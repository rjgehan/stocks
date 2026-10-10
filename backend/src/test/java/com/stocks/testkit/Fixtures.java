package com.stocks.testkit;

import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.money.Rounding;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.security.Scopes;
import com.stocks.security.ApiKeys;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-line test data (§26) written straight to the §8 tables with the same JDBC typing rules as production
 * ({@link Jdbc#ts}), so it works on H2 and PostgreSQL alike. Rows are internally consistent (ledger, transfers,
 * balances, units, lots), so invariants I1–I5 and I9 hold after each call; it bypasses the engine, so use it for
 * read-side and web tests, not to test the writers themselves.
 *
 * <pre>{@code
 * Fixtures f = new Fixtures(jdbc, time);
 * Fixtures.PlayerRef alice = f.human("alice");
 * Fixtures.AccountRef acc = f.account(alice, "500.00");
 * f.deposit(acc, "100.00");
 * f.position(acc, "AAPL", "1.5", "227.4000");
 * String key = f.apiKey(f.ai("claude", alice), Scopes.ALL, pepper);
 * }</pre>
 */
public final class Fixtures {

  /** Password of every fixture human. */
  public static final String PASSWORD = "password123!";
  /** {@code {bcrypt}} hash of {@link #PASSWORD} at cost 4 (fast; verifies with any bcrypt encoder). */
  public static final String PASSWORD_HASH = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);

  public record PlayerRef(long id, String publicId, String handle) {}

  public record AccountRef(long id, String publicId, long playerId) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final PublicId publicId;
  private final SecureRandom random = new SecureRandom();

  public Fixtures(JdbcClient jdbc, TimeSource time) {
    this.jdbc = jdbc;
    this.time = time;
    this.publicId = new PublicId(time);
  }

  // ------------------------------------------------------------------------------------------- players

  public PlayerRef human(String handle) {
    return player(handle, "HUMAN", "PLAYER", null, null, PASSWORD_HASH);
  }

  public PlayerRef admin(String handle) {
    return player(handle, "HUMAN", "ADMIN", null, null, PASSWORD_HASH);
  }

  public PlayerRef ai(String handle, PlayerRef owner) {
    return player(handle, "AI", "PLAYER", "Claude", owner.id(), null);
  }

  /** Sets {@code player.status} (ACTIVE, FROZEN, DISABLED). */
  public void status(PlayerRef player, String status) {
    jdbc.sql("UPDATE player SET status = ? WHERE id = ?").params(status, player.id()).update();
  }

  private PlayerRef player(String handle, String kind, String role, String aiModel, Long owner, String hash) {
    String pid = publicId.next(Prefix.PLAYER);
    jdbc.sql("""
            INSERT INTO player (public_id, handle, display_name, kind, role, status, ai_model, owner_player_id,
                                password_hash, created_at)
            VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)""")
        .params(pid, handle, handle, kind, role, aiModel, owner, hash, Jdbc.ts(time.now()))
        .update();
    long id = jdbc.sql("SELECT id FROM player WHERE public_id = ?").param(pid).query(Long.class).single();
    return new PlayerRef(id, pid, handle);
  }

  // ------------------------------------------------------------------------------------------- API keys

  /** Issues an API key and returns the {@code stk_…} token (HMAC with {@code pepper}, §17.2). */
  public String apiKey(PlayerRef player, Set<String> scopes, String pepper) {
    return apiKey(player, scopes, pepper, null);
  }

  public String apiKey(PlayerRef player, Set<String> scopes, String pepper, Instant expiresAtOrNull) {
    String keyId = ApiKeys.newKeyId(random);
    String secret = ApiKeys.newSecret(random);
    jdbc.sql("""
            INSERT INTO api_key (key_id, player_id, name, secret_hmac, scopes, created_by, created_at, expires_at)
            VALUES (?, ?, 'fixture', ?, ?, ?, ?, ?)""")
        .params(keyId, player.id(), ApiKeys.hmacHex(pepper, secret), String.join(" ", new TreeSet<>(scopes)),
            player.id(), Jdbc.ts(time.now()), Jdbc.ts(expiresAtOrNull))
        .update();
    return ApiKeys.format(keyId, secret);
  }

  /** All four scopes. */
  public String apiKey(PlayerRef player, String pepper) {
    return apiKey(player, Scopes.ALL, pepper);
  }

  public void revokeApiKey(String token) {
    String keyId = ApiKeys.parse(token).orElseThrow().keyId();
    jdbc.sql("UPDATE api_key SET revoked_at = ? WHERE key_id = ?").params(Jdbc.ts(time.now()), keyId).update();
  }

  // ------------------------------------------------------------------------------------------- money

  /** Run 1, funded with {@code startingCash} as an INITIAL transfer at unit value 1 (§16.2). */
  public AccountRef account(PlayerRef player, String startingCash) {
    BigDecimal cash = new BigDecimal(startingCash).setScale(2, RoundingMode.UNNECESSARY);
    Instant now = time.now();
    String accPid = publicId.next(Prefix.ACCOUNT);
    jdbc.sql("""
            INSERT INTO account (public_id, player_id, run_no, status, starting_cash, opened_at)
            VALUES (?, ?, 1, 'ACTIVE', ?, ?)""")
        .params(accPid, player.id(), cash, Jdbc.ts(now)).update();
    long accountId = jdbc.sql("SELECT id FROM account WHERE public_id = ?").param(accPid).query(Long.class).single();
    AccountRef account = new AccountRef(accountId, accPid, player.id());
    flow(account, "SYSTEM", "INITIAL", "INITIAL_FUNDING", cash, BigDecimal.ONE, "Initial funding");
    return account;
  }

  /** An admin deposit at {@code unitValue} 1. */
  public void deposit(AccountRef account, String amount) {
    deposit(account, amount, BigDecimal.ONE);
  }

  /** An admin deposit at the given unit value (units += amount / U, §16.2). */
  public void deposit(AccountRef account, String amount, BigDecimal unitValue) {
    BigDecimal a = new BigDecimal(amount).setScale(2, RoundingMode.UNNECESSARY);
    flow(account, "SINGLE", "DEPOSIT", "DEPOSIT", a, unitValue, "Fixture deposit");
  }

  private void flow(AccountRef account, String scope, String transferType, String ledgerType, BigDecimal amount,
                    BigDecimal unitValue, String memo) {
    Instant now = time.now();
    LocalDate today = time.todayEt();
    String batchPid = publicId.next(Prefix.TRANSFER_BATCH);
    jdbc.sql("""
            INSERT INTO transfer_batch (public_id, scope, transfer_type, amount, memo, applied_count, created_at)
            VALUES (?, ?, ?, ?, ?, 1, ?)""")
        .params(batchPid, scope, transferType, amount, memo, Jdbc.ts(now)).update();
    long batchId = jdbc.sql("SELECT id FROM transfer_batch WHERE public_id = ?").param(batchPid)
        .query(Long.class).single();
    BigDecimal units = Rounding.UNIT.divide(amount, unitValue);
    String xfrPid = publicId.next(Prefix.CASH_TRANSFER);
    jdbc.sql("""
            INSERT INTO cash_transfer (public_id, batch_id, account_id, transfer_type, amount, is_contribution, status,
                                       memo, unit_value, units_delta, effective_at)
            VALUES (?, ?, ?, ?, ?, TRUE, 'APPLIED', ?, ?, ?, ?)""")
        .params(xfrPid, batchId, account.id(), transferType, amount, memo, unitValue, units, Jdbc.ts(now)).update();
    long xfrId = jdbc.sql("SELECT id FROM cash_transfer WHERE public_id = ?").param(xfrPid)
        .query(Long.class).single();
    BigDecimal balance = cash(account).add(amount);
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, transfer_id, memo, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId.next(Prefix.LEDGER), account.id(), ledgerType, amount, balance, today, today, Jdbc.ts(now),
            xfrId, memo, "XFER:" + xfrId)
        .update();
    jdbc.sql("""
            UPDATE account SET cash_balance = ?, net_contributions = net_contributions + ?, units = units + ?
             WHERE id = ?""")
        .params(balance, amount, units, account.id()).update();
  }

  public BigDecimal cash(AccountRef account) {
    return jdbc.sql("SELECT cash_balance FROM account WHERE id = ?").param(account.id())
        .query(BigDecimal.class).single();
  }

  // ------------------------------------------------------------------------------------------- positions

  /** A tradable, fractionable MEGA-tier asset (no-op if it exists). */
  public void asset(String symbol) {
    long n = jdbc.sql("SELECT COUNT(*) FROM asset WHERE symbol = ?").param(symbol).query(Long.class).single();
    if (n == 0) {
      jdbc.sql("""
              INSERT INTO asset (symbol, name, exchange, asset_type, tradable, fractionable, liquidity_tier, status,
                                 source, updated_at)
              VALUES (?, ?, 'NASDAQ', 'COMMON_STOCK', TRUE, TRUE, 'MEGA', 'ACTIVE', 'manual', ?)""")
          .params(symbol, symbol + " Inc.", Jdbc.ts(time.now())).update();
    }
  }

  /**
   * A filled MARKET buy of {@code qty} at {@code price} today (no fees), with its execution, ledger row (settles the
   * next calendar day), position and FIFO lot. The account needs the cash (DB CHECK: cash ≥ 0).
   */
  public void position(AccountRef account, String symbol, String qty, String price) {
    asset(symbol);
    BigDecimal q = new BigDecimal(qty);
    BigDecimal p = new BigDecimal(price);
    BigDecimal principal = Rounding.PRINCIPAL_BUY.multiply(q, p);
    Instant now = time.now();
    LocalDate today = time.todayEt();
    LocalDate settle = today.plusDays(1);
    boolean fractional = q.stripTrailingZeros().scale() > 0;
    String ordPid = publicId.next(Prefix.ORDER);
    jdbc.sql("""
            INSERT INTO trade_order (public_id, account_id, player_id, client_order_id, request_hash, symbol, side,
                                     order_type, qty, fractional, time_in_force, extended_hours, status,
                                     filled_qty, filled_principal, avg_fill_price, session_date, received_at,
                                     accepted_at, eligible_at, last_fill_at, expires_at, done_at, channel)
            VALUES (?, ?, ?, ?, ?, ?, 'BUY', 'MARKET', ?, ?, 'DAY', FALSE, 'FILLED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WEB')""")
        .params(ordPid, account.id(), account.playerId(), "fixture-" + UUID.randomUUID(), "0".repeat(64), symbol, q,
            fractional, q, principal, p, today, Jdbc.ts(now), Jdbc.ts(now), Jdbc.ts(now), Jdbc.ts(now), Jdbc.ts(now),
            Jdbc.ts(now))
        .update();
    long orderId = jdbc.sql("SELECT id FROM trade_order WHERE public_id = ?").param(ordPid).query(Long.class).single();
    String exePid = publicId.next(Prefix.EXECUTION);
    jdbc.sql("""
            INSERT INTO execution (public_id, order_id, account_id, symbol, side, qty, price, principal, net_amount,
                                   liquidity, executed_at, trade_date, settle_date, ref_source, ref_quality)
            VALUES (?, ?, ?, ?, 'BUY', ?, ?, ?, ?, 'TOUCH', ?, ?, ?, 'fixture', 'SIMULATED')""")
        .params(exePid, orderId, account.id(), symbol, q, p, principal, principal.negate(), Jdbc.ts(now), today, settle)
        .update();
    long exeId = jdbc.sql("SELECT id FROM execution WHERE public_id = ?").param(exePid).query(Long.class).single();
    BigDecimal balance = cash(account).subtract(principal);
    jdbc.sql("""
            INSERT INTO cash_ledger (public_id, account_id, entry_type, amount, balance_after, trade_date, settle_date,
                                     posted_at, execution_id, symbol, idempotency_key)
            VALUES (?, ?, 'TRADE_BUY', ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(publicId.next(Prefix.LEDGER), account.id(), principal.negate(), balance, today, settle, Jdbc.ts(now),
            exeId, symbol, "EXEC:" + exeId)
        .update();
    jdbc.sql("UPDATE account SET cash_balance = ? WHERE id = ?").params(balance, account.id()).update();
    jdbc.sql("""
            INSERT INTO position_lot (account_id, symbol, open_execution_id, trade_date, opened_at, qty_original,
                                      qty_remaining, cost_remaining)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(account.id(), symbol, exeId, today, Jdbc.ts(now), q, q, principal).update();
    long existing = jdbc.sql("SELECT COUNT(*) FROM position WHERE account_id = ? AND symbol = ?")
        .params(account.id(), symbol).query(Long.class).single();
    if (existing == 0) {
      jdbc.sql("""
              INSERT INTO position (account_id, symbol, qty, cost_basis, opened_at, updated_at)
              VALUES (?, ?, ?, ?, ?, ?)""")
          .params(account.id(), symbol, q, principal, Jdbc.ts(now), Jdbc.ts(now)).update();
    } else {
      jdbc.sql("""
              UPDATE position SET qty = qty + ?, cost_basis = cost_basis + ?, updated_at = ?
               WHERE account_id = ? AND symbol = ?""")
          .params(q, principal, Jdbc.ts(now), account.id(), symbol).update();
    }
  }
}
