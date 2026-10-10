package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.trading.account.AccountCommands;
import com.stocks.trading.account.TransferCommands;
import com.stocks.trading.config.EngineProperties;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.AccountStore;
import com.stocks.trading.store.OrderStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Review regression (§12.9): a bootstrap that throws used to leave the engine in STARTING, un-bootstrapped, while it
 * still accepted orders that reserved buying power and were never activated, filled or expired; an admin resume never
 * reloaded the open orders. Now the engine is HALTED with a "Bootstrap failed" reason, refuses non-admin commands,
 * a resume retries the bootstrap first, and the engine-main loop retries it with backoff.
 */
class EngineBootstrapFailureTest {

  private final OrderStore orderStore = mock(OrderStore.class);
  private final InvariantChecker invariants = mock(InvariantChecker.class);
  private final EngineCore core = new EngineCore(mock(PlatformTransactionManager.class),
      mock(ApplicationEventPublisher.class), mock(OrderHandler.class), mock(Evaluator.class),
      mock(AccountCommands.class), mock(TransferCommands.class), invariants, orderStore, mock(AccountStore.class),
      mock(TradingClock.class), mock(MarketDataService.class), new OpenOrderIndex(), mock(JdbcClient.class),
      mock(com.stocks.trading.corpactions.CorporateActionApplier.class));

  @Test
  void aFailedBootstrapHaltsTheEngineAndAResumeRetriesIt() {
    when(orderStore.open()).thenThrow(new DataAccessResourceFailureException("db down")).thenReturn(List.of());
    when(invariants.check()).thenReturn(new InvariantResult(Instant.EPOCH, true, List.of()));

    assertThatThrownBy(() -> EngineThread.runAsEngine(() -> {
      core.bootstrap();
      return null;
    })).isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(core.isBootstrapped()).isFalse();
    assertThat(core.state()).isEqualTo(EngineState.HALTED);
    assertThat(core.status(0).haltReason()).startsWith("Bootstrap failed: ").contains("db down");

    // orders are refused instead of reserving buying power nothing would release
    assertThatThrownBy(() -> EngineThread.runAsEngine(() -> core.execute(new EngineCommand.SweepCmd())))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ENGINE_HALTED));

    // the admin resume reloads the open orders first, then runs
    EngineThread.runAsEngine(() -> core.execute(new EngineCommand.ResumeEngineCmd(1L)));
    assertThat(core.isBootstrapped()).isTrue();
    assertThat(core.state()).isEqualTo(EngineState.RUNNING);
    assertThat(core.status(0).haltReason()).isNull();
  }

  @Test
  void aResumeThatCannotBootstrapStaysHalted() {
    when(orderStore.open()).thenThrow(new DataAccessResourceFailureException("db down"));
    assertThatThrownBy(() -> EngineThread.runAsEngine(() -> core.execute(new EngineCommand.ResumeEngineCmd(1L))))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ENGINE_HALTED));
    assertThat(core.state()).isEqualTo(EngineState.HALTED);
    assertThat(core.isBootstrapped()).isFalse();
  }

  @Test
  void theEngineLoopRetriesAFailedBootstrap() throws InterruptedException {
    EngineCore loopCore = mock(EngineCore.class);
    AtomicInteger attempts = new AtomicInteger();
    when(loopCore.isBootstrapped()).thenReturn(false);
    // count with an answer: verify(timeout) would hold the mock's monitor (bootstrap() is synchronized)
    doAnswer(inv -> {
      attempts.incrementAndGet();
      throw new DataAccessResourceFailureException("db down");
    }).when(loopCore).bootstrap();
    EngineRunner runner = new EngineRunner(loopCore, new EngineProperties(null, null, null, Duration.ofMillis(20),
        null, null, null, true));
    runner.start();
    try {
      // the first attempt at once, the retry after the 1 s backoff
      for (int i = 0; i < 200 && attempts.get() < 2; i++) {
        Thread.sleep(20);
      }
    } finally {
      runner.stop();
    }
    assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
  }
}
