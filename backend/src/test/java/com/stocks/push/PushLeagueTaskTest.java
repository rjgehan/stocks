package com.stocks.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.web.Page;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedItemKind;
import com.stocks.contract.portfolio.dto.FeedItemDto.FeedPayloadDto;
import com.stocks.push.FakePushService.Browser;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestTimes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §19.2 league pushes: a NEW_LEADER feed item that appears after boot reaches players who want league pushes, once. */
class PushLeagueTaskTest {

  PushKit k;
  PerformanceQueries performance = mock(PerformanceQueries.class);
  List<FeedItemDto> feed = new ArrayList<>();
  PushLeagueTask task;

  @BeforeEach
  void setUp() throws Exception {
    k = new PushKit();
    when(performance.feed(any(), anyInt())).thenAnswer(inv -> new Page<>(List.copyOf(feed), null));
    task = new PushLeagueTask(performance, k.push);
  }

  @AfterEach
  void tearDown() {
    k.close();
  }

  static FeedItemDto item(String id, FeedItemKind kind, String displayName) {
    PlayerSummaryDto p = new PlayerSummaryDto("ply_1", "claude", displayName, PlayerKind.AI, "Claude", null,
        TestTimes.START, 0);
    return new FeedItemDto(id, TestTimes.START, kind, p, null,
        new FeedPayloadDto(null, null, null, null, null, "WEEK", null), List.of());
  }

  @Test
  void onlyNewLeadersAfterBootArePushedOnce() throws Exception {
    PlayerRef alice = k.fx.human("alice");
    PlayerRef bob = k.fx.human("bob");
    Browser a = k.service.subscription();
    k.subscriptions.upsert("psh_a", alice.id(), a.endpoint(), a.p256dh(), a.authB64(), null, "A", k.time.now());
    Browser b = k.service.subscription();
    k.subscriptions.upsert("psh_b", bob.id(), b.endpoint(), b.p256dh(), b.authB64(), null, "B", k.time.now());
    k.subscriptions.setPreference(bob.id(), com.stocks.common.domain.PushCategory.LEAGUE, false, k.time.now());

    feed.add(item("fde_old", FeedItemKind.NEW_LEADER, "Bob"));
    task.tick();                                                  // primes: old news is never pushed
    feed.addFirst(item("fde_fill", FeedItemKind.FILL, "Bob"));
    feed.addFirst(item("fde_new", FeedItemKind.NEW_LEADER, "Claude"));
    task.tick();
    task.tick();                                                  // seen: not again
    k.service.await(1);
    Thread.sleep(200);
    assertThat(k.service.received).hasSize(1);
    String json = a.decrypt(k.service.received.get(0).body());
    assertThat(json).contains("\"title\":\"New weekly leader\"").contains("Claude now leads the weekly leaderboard.")
        .contains("\"url\":\"/league\"").doesNotContain("ply_1");
  }
}
