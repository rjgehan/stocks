package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.events.OrderChanged;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.EngineControl;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.testkit.TradingTestConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The production engine thread (D2, §4, §12.2): with {@code stocks.engine.auto-start=true} every command runs on
 * {@code engine-main}, concurrent duplicate submissions resolve to exactly one order (§11.7), events are published
 * after commit from the engine thread, a market update from another thread is evaluated by the loop, and
 * {@link EngineControl} halts and resumes it.
 */
@SpringBootTest(properties = "stocks.engine.auto-start=true")
@ActiveProfiles("test")
@Import({TradingTestConfig.class, EngineThreadIT.Recorder.class})
class EngineThreadIT {

  @Autowired MutableTimeSource time;
  @Autowired ScriptedMarketData md;
  @Autowired JdbcClient jdbc;
  @Autowired OrderService orders;
  @Autowired OrderQueries orderQueries;
  @Autowired AccountService accountService;
  @Autowired AccountQueries accountQueries;
  @Autowired EngineControl control;
  @Autowired InvariantChecker invariants;
  @Autowired Recorder recorder;

  /** Records the thread every {@code OrderChanged} is published on (a bean through {@code @Import}). */
  static class Recorder {
    final ConcurrentLinkedQueue<String> threads = new ConcurrentLinkedQueue<>();

    @EventListener
    void on(OrderChanged e) {
      threads.add(Thread.currentThread().getName());
    }
  }

  @Test
  void concurrentDuplicatesResolveToOneOrderOnTheEngineThread() throws Exception {
    time.set(TestTimes.START);
    Fixtures fx = new Fixtures(jdbc, time);
    Fixtures.PlayerRef ref = fx.human("thr" + System.nanoTime() % 100000);
    accountService.openInitialAccount(ref.id(), new BigDecimal("500.00"), ref.id());
    PlayerPrincipal who = new PlayerPrincipal(ref.id(), ref.publicId(), ref.handle(), PlayerKind.HUMAN,
        PlayerRole.PLAYER, Scopes.ALL, AuthMethod.SESSION, null, null, TestTimes.START);
    RequestMeta meta = new RequestMeta("req", Channel.WEB, "127.0.0.1", "it", null, null);
    String s = "ET" + System.nanoTime() % 100000;
    md.withAsset(s).quote(s, time.now(), "9.99", 300, "10.00", 300);
    PlaceOrderRequest req = new PlaceOrderRequest("dup-1", s, Side.BUY, OrderType.MARKET, new BigDecimal("3"), null,
        null, null, null, null, TimeInForce.DAY, false, null, null, Set.of());

    int n = 24;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<PlaceOrderResponse>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      futures.add(pool.submit((Callable<PlaceOrderResponse>) () -> {
        go.await();
        return orders.place(who, req, meta);
      }));
    }
    go.countDown();
    List<PlaceOrderResponse> results = new ArrayList<>();
    for (Future<PlaceOrderResponse> f : futures) {
      results.add(f.get());
    }
    pool.shutdown();
    assertThat(results).extracting(r -> r.order().orderId()).containsOnly(results.get(0).order().orderId());
    assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
    long accountId = accountQueries.activeAccountId(ref.id());
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ?").param(accountId)
        .query(Integer.class).single()).isEqualTo(1);
    assertThat(recorder.threads).isNotEmpty().containsOnly(EngineThread.NAME);

    // a market update from a feed thread: engine-main evaluates the symbol and fills the order
    String orderId = results.get(0).order().orderId();
    time.advance(Duration.ofSeconds(1));
    Thread feed = new Thread(() -> {
      md.quote(s, time.now(), "9.99", 300, "10.00", 300);
      md.publish(s);
    }, "feed");
    feed.start();
    feed.join();
    OrderStatus status = null;
    for (int i = 0; i < 100 && status != OrderStatus.FILLED; i++) {
      Thread.sleep(50);
      status = orderQueries.byPublicId(accountId, orderId, false).orElseThrow().status();
    }
    assertThat(status).isEqualTo(OrderStatus.FILLED);

    // EngineControl: halt rejects orders; resume checks the invariants first
    assertThat(control.halt("maintenance", ref.id()).state()).isEqualTo(EngineState.HALTED);
    assertThat(control.status().haltReason()).isEqualTo("maintenance");
    try {
      orders.place(who, new PlaceOrderRequest("dup-2", s, Side.BUY, OrderType.MARKET, BigDecimal.ONE, null, null,
          null, null, null, TimeInForce.DAY, false, null, null, Set.of()), meta);
      throw new AssertionError("a halted engine accepted an order");
    } catch (com.stocks.common.error.ApiException e) {
      assertThat(e.code()).isEqualTo(com.stocks.common.error.ErrorCode.ENGINE_HALTED);
    }
    assertThat(control.resume(ref.id()).state()).isEqualTo(EngineState.RUNNING);
    InvariantResult r = invariants.check();
    assertThat(r.failures()).isEmpty();
    assertThat(control.status().lastInvariants().ok()).isTrue();
  }
}
