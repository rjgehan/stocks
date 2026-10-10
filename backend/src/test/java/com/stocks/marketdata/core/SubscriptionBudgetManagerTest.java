package com.stocks.marketdata.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.LeaseHandle;
import com.stocks.contract.marketdata.LeasePriority;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** §15.5 budget manager: never above max − 2, priority eviction, hysteresis, TTL bot leases, ref-counting. */
class SubscriptionBudgetManagerTest {

  final MutableTimeSource time = TestTimes.clock();
  final SubscriptionBudgetManager budget = new SubscriptionBudgetManager(time);

  @Test
  void neverExceedsTheLimitAndPrefersHigherPriorities() {
    for (int i = 0; i < 40; i++) {
      budget.lease("V" + i, LeasePriority.VIEWING, "ws:" + i);
    }
    budget.lease("ORD", LeasePriority.ORDER_RESTING, "order:1");
    budget.lease("SPY", LeasePriority.PINNED, "pin:SPY");
    List<String> chosen = budget.select(30, null, Map.of());
    assertThat(chosen).hasSize(28);
    assertThat(chosen.subList(0, 2)).containsExactly("SPY", "ORD");
  }

  @Test
  void leaseCountAndRecencyBreakTies() {
    budget.lease("A", LeasePriority.VIEWING, "ws:1");
    budget.lease("B", LeasePriority.VIEWING, "ws:1");
    budget.lease("B", LeasePriority.VIEWING, "ws:2");
    budget.lease("C", LeasePriority.VIEWING, "ws:3");
    assertThat(budget.select(5, null, Map.of())).containsExactly("B", "C", "A");
  }

  @Test
  void hysteresisKeepsRecentSubscriptionsAgainstEqualPriorities() {
    for (int i = 0; i < 3; i++) {
      budget.lease("OLD" + i, LeasePriority.LIST, "ws:" + i);
    }
    Map<String, Instant> subscribed = new HashMap<>();
    budget.select(5, null, subscribed).forEach(s -> subscribed.put(s, time.now()));
    assertThat(subscribed).containsOnlyKeys("OLD0", "OLD1", "OLD2");
    time.advance(Duration.ofSeconds(10));
    budget.lease("NEW", LeasePriority.LIST, "ws:9");             // equal priority, newer: would win the tie
    budget.release("OLD2", "ws:2");                              // OLD2 is no longer wanted at all
    List<String> next = budget.select(5, null, subscribed);
    assertThat(next).contains("OLD0", "OLD1").hasSizeLessThanOrEqualTo(3);
    // a strictly higher priority takes a slot from a recent subscription
    budget.lease("HOT", LeasePriority.ORDER_TRIGGER, "order:7");
    List<String> withHot = budget.select(5, null, subscribed);
    assertThat(withHot).contains("HOT").hasSizeLessThanOrEqualTo(3);
    // after 60 s, hysteresis is gone
    time.advance(Duration.ofSeconds(61));
    assertThat(budget.select(5, null, subscribed)).hasSize(3).contains("HOT", "NEW");
  }

  @Test
  void botLeasesExpireAfterSixtySecondsUnlessRenewed() {
    budget.lease("AAPL", LeasePriority.LIST, "bot:42");
    budget.lease("MSFT", LeasePriority.LIST, "bot:42");
    time.advance(Duration.ofSeconds(50));
    budget.lease("AAPL", LeasePriority.LIST, "bot:42");          // renewed
    time.advance(Duration.ofSeconds(20));
    assertThat(budget.leasedSymbols()).containsExactly("AAPL");
  }

  @Test
  void handlesReleaseOnlyTheirOwnLeaseAndOwnersReleaseEverything() {
    LeaseHandle h = budget.lease("AAPL", LeasePriority.VIEWING, "ws:1");
    budget.lease("AAPL", LeasePriority.ORDER_RESTING, "order:9");
    budget.lease("MSFT", LeasePriority.VIEWING, "ws:1");
    h.close();
    assertThat(budget.bestPriorities()).containsEntry("AAPL", LeasePriority.ORDER_RESTING)
        .containsEntry("MSFT", LeasePriority.VIEWING);
    budget.releaseOwner("ws:1");
    assertThat(budget.leasedSymbols()).containsExactly("AAPL");
    budget.releaseOwner("order:9");
    assertThat(budget.leasedSymbols()).isEmpty();
  }

  @Test
  void finnhubStreamCarriesOnlyItsPriorities() {
    budget.lease("V", LeasePriority.VIEWING, "ws:1");
    budget.lease("H", LeasePriority.HOLDING, "hold:H");
    budget.lease("T", LeasePriority.ORDER_TRIGGER, "order:1");
    assertThat(budget.select(50, SubscriptionBudgetManager.FINNHUB_PRIORITIES, Map.of())).containsExactly("T", "H");
  }
}
