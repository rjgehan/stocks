package com.stocks.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The test kit's {@link Fixtures} write consistent rows on both databases (run under {@code -Ppg-it} too): the
 * ledger, transfer, unit and lot invariants of §8.4 (I1–I5, I9) hold after each call.
 */
class FixturesTest {

  @Test
  void playersAccountsDepositsAndPositionsAreConsistent() {
    TestDb db = TestDb.fresh();
    MutableTimeSource time = TestTimes.clock();
    Fixtures fx = new Fixtures(db.jdbc(), time);
    Fixtures.PlayerRef alice = fx.human("alice");
    Fixtures.PlayerRef claude = fx.ai("claude", alice);
    Fixtures.AccountRef acc = fx.account(alice, "500.00");
    fx.deposit(acc, "100.00");
    fx.position(acc, "AAPL", "1.5", "227.4000");
    fx.position(acc, "AAPL", "1", "200.0000");
    fx.account(claude, "500.00");

    assertThat(fx.cash(acc)).isEqualByComparingTo("58.90"); // 500 + 100 - 341.10 - 200.00
    JdbcClient j = db.jdbc();
    BigDecimal ledgerSum = j.sql("SELECT SUM(amount) FROM cash_ledger WHERE account_id = ?").param(acc.id())
        .query(BigDecimal.class).single();
    BigDecimal lastBalance = j.sql("SELECT balance_after FROM cash_ledger WHERE account_id = ? ORDER BY id DESC")
        .param(acc.id()).query(BigDecimal.class).list().get(0);
    assertThat(ledgerSum).as("I1/I2").isEqualByComparingTo(fx.cash(acc)).isEqualByComparingTo(lastBalance);
    assertThat(j.sql("SELECT net_contributions FROM account WHERE id = ?").param(acc.id()).query(BigDecimal.class)
        .single()).as("I3").isEqualByComparingTo("600.00");
    assertThat(j.sql("SELECT units FROM account WHERE id = ?").param(acc.id()).query(BigDecimal.class).single())
        .as("I4").isEqualByComparingTo(j.sql("SELECT SUM(units_delta) FROM cash_transfer WHERE account_id = ?")
            .param(acc.id()).query(BigDecimal.class).single());
    assertThat(j.sql("SELECT qty FROM position WHERE account_id = ? AND symbol = 'AAPL'").param(acc.id())
        .query(BigDecimal.class).single()).as("I5").isEqualByComparingTo("2.5")
        .isEqualByComparingTo(j.sql("SELECT SUM(qty_remaining) FROM position_lot WHERE account_id = ?")
            .param(acc.id()).query(BigDecimal.class).single());
    assertThat(j.sql("SELECT COUNT(*) FROM execution e WHERE account_id = ? AND NOT EXISTS "
            + "(SELECT 1 FROM cash_ledger l WHERE l.execution_id = e.id AND l.amount = e.net_amount)")
        .param(acc.id()).query(Long.class).single()).as("I9").isZero();
  }
}
