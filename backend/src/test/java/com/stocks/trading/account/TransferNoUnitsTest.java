package com.stocks.trading.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Review regression (§16.2, §21.2): a WITHDRAWAL to ALL used to fail for every player (INTERNAL_ERROR on
 * {@code ck_account_units_nonneg}) when one account had been emptied by a full withdrawal and then given cash by a
 * PnL correction. The correction is now skipped with {@code NO_UNITS}, and the ALL batch skips the empty account and
 * applies to the others. Its own context (a fresh database), so "all" means exactly this class's players.
 */
@TestPropertySource(properties = "stocks.engine.seed=transfer-no-units")
class TransferNoUnitsTest extends TradingTestBase {

  @Test
  void aWithdrawalToAllSkipsAnAccountWithoutUnitsAndAppliesToTheRest() {
    Player empty = human();
    Player funded = human();
    long admin = fx.admin("root" + next()).id();
    transfers.apply(new CashTransferCommand(TransferScope.SINGLE, empty.handle(), Set.of(), TransferType.WITHDRAWAL,
        new BigDecimal("500.00"), null, "cash out", false, false), null, admin);
    TransferBatchResultDto fix = transfers.apply(new CashTransferCommand(TransferScope.SINGLE, empty.handle(), Set.of(),
        TransferType.ADJUSTMENT, new BigDecimal("10.00"), AdjustmentTreatment.PNL_CORRECTION, "fix", false, false),
        null, admin);
    assertThat(fix.applied()).isZero();

    TransferBatchResultDto r = transfers.apply(new CashTransferCommand(TransferScope.ALL, null, Set.of(),
        TransferType.WITHDRAWAL, new BigDecimal("5.00"), null, "fee", false, false), null, admin);
    assertThat(r.applied()).isEqualTo(1);
    assertThat(r.results()).filteredOn(x -> x.handle().equals(empty.handle())).singleElement()
        .satisfies(x -> assertThat(x.status()).isEqualTo(TransferStatus.SKIPPED));
    assertThat(r.results()).filteredOn(x -> x.handle().equals(funded.handle())).singleElement()
        .satisfies(x -> assertThat(x.status()).isEqualTo(TransferStatus.APPLIED));
    assertThat(account(funded).cash().total()).isEqualByComparingTo("495.00");
    assertThat(account(empty).cash().total()).isEqualByComparingTo("0.00");
  }
}
