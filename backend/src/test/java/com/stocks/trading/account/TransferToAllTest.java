package com.stocks.trading.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * A transfer to ALL players (§21.2 step 2): every ACTIVE or FROZEN player of the chosen kinds with an ACTIVE account,
 * in one batch and one transaction. Its own context (a fresh database), so "all" means exactly this class's players.
 */
@TestPropertySource(properties = "stocks.engine.seed=transfer-to-all")
class TransferToAllTest extends TradingTestBase {

  @Test
  void aDepositToAllReachesEveryActiveOrFrozenPlayerOfTheChosenKinds() {
    Player alice = human();
    Player bob = human();
    Player frozen = human();
    Player disabled = human();
    Player bot = bot();
    fx.status(frozen.ref(), "FROZEN");
    fx.status(disabled.ref(), "DISABLED");
    long admin = fx.admin("root" + next()).id();

    CashTransferCommand toHumans = new CashTransferCommand(TransferScope.ALL, null, Set.of(PlayerKind.HUMAN),
        TransferType.DEPOSIT, new BigDecimal("100.00"), null, "Friday bonus", false, false);
    CashTransferPreviewDto preview = transfers.preview(toHumans);
    // the bots' owners are humans with no account: not targets
    assertThat(preview.accounts()).extracting(a -> a.handle())
        .containsExactlyInAnyOrder(alice.handle(), bob.handle(), frozen.handle());
    long ledgerBefore = jdbc.sql("SELECT COUNT(*) FROM cash_ledger").query(Long.class).single();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM cash_ledger").query(Long.class).single()).isEqualTo(ledgerBefore);

    TransferBatchResultDto r = transfers.apply(toHumans, null, admin);
    assertThat(r.scope()).isEqualTo(TransferScope.ALL);
    assertThat(r.applied()).isEqualTo(3);
    assertThat(r.results()).allMatch(x -> x.status() == TransferStatus.APPLIED);
    assertThat(account(alice).cash().total()).isEqualByComparingTo("600.00");
    assertThat(account(frozen).cash().total()).isEqualByComparingTo("600.00");
    assertThat(account(disabled).cash().total()).isEqualByComparingTo("500.00");
    assertThat(account(bot).cash().total()).isEqualByComparingTo("500.00");

    CashTransferCommand toEveryone = new CashTransferCommand(TransferScope.ALL, null, Set.of(),
        TransferType.DEPOSIT, new BigDecimal("1.00"), null, "everyone", false, false);
    assertThat(transfers.apply(toEveryone, null, admin).applied()).isEqualTo(4);
    assertThat(account(bot).cash().total()).isEqualByComparingTo("501.00");
    assertThat(account(bob).netContributions()).isEqualByComparingTo("601.00");
  }
}
