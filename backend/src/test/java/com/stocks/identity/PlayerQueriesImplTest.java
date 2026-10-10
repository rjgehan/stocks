package com.stocks.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §5.3 {@code PlayerQueries} on a fresh database (embedded PostgreSQL under -Ppg-it). */
class PlayerQueriesImplTest {

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;

  @org.junit.jupiter.api.AfterEach
  void closeDb() {
    db.close();
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    fx = new Fixtures(db.jdbc(), time);
  }

  @Test
  void summariesFiltersAndResets() {
    PlayerQueriesImpl q = new PlayerQueriesImpl(db.jdbc(), TestProperties.stocks());
    Fixtures.PlayerRef alice = fx.human("alice");
    Fixtures.PlayerRef gone = fx.human("gone");
    Fixtures.PlayerRef bot = fx.ai("claude", alice);
    fx.status(gone, "DISABLED");
    fx.account(alice, "500.00");
    // a second run (reset): run 1 closed, run 2 active
    db.jdbc().sql("UPDATE account SET status = 'CLOSED', close_reason = 'RESET', closed_at = ? WHERE player_id = ?")
        .params(Jdbc.ts(time.now()), alice.id()).update();
    db.jdbc().sql("INSERT INTO account (public_id, player_id, run_no, status, starting_cash, opened_at) "
        + "VALUES ('acc_run2', ?, 2, 'ACTIVE', 500.00, ?)").params(alice.id(), Jdbc.ts(time.now())).update();

    PlayerSummaryDto a = q.byHandle("ALICE").orElseThrow();
    assertThat(a.playerId()).isEqualTo(alice.publicId());
    assertThat(a.kind()).isEqualTo(PlayerKind.HUMAN);
    assertThat(a.resets()).isEqualTo(1);
    assertThat(a.memberSince()).isEqualTo(TestTimes.START);
    assertThat(q.byId(bot.id()).orElseThrow().resets()).isZero();
    assertThat(q.byHandle("nobody")).isEmpty();
    assertThat(q.byHandle(null)).isEmpty();

    assertThat(q.all(null, true)).extracting(PlayerSummaryDto::handle).containsExactly("alice", "claude", "gone");
    assertThat(q.all(null, false)).extracting(PlayerSummaryDto::handle).containsExactly("alice", "claude");
    assertThat(q.all(PlayerKind.AI, false)).extracting(PlayerSummaryDto::handle).containsExactly("claude");
  }

  @Test
  void statusIsCachedUntilEvicted() {
    PlayerQueriesImpl q = new PlayerQueriesImpl(db.jdbc(),
        TestProperties.stocks("stocks.security.player-status-cache-ttl", "60s"));
    Fixtures.PlayerRef p = fx.human("bob");
    assertThat(q.status(p.id())).isEqualTo(PlayerStatus.ACTIVE);
    fx.status(p, "FROZEN");
    assertThat(q.status(p.id())).isEqualTo(PlayerStatus.ACTIVE);
    q.evict(p.id());
    assertThat(q.status(p.id())).isEqualTo(PlayerStatus.FROZEN);
    assertThatThrownBy(() -> q.status(-1)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));

    PlayerQueriesImpl uncached = new PlayerQueriesImpl(db.jdbc(), TestProperties.stocks(
        "stocks.security.player-status-cache-ttl", "0s"));
    fx.status(p, "DISABLED");
    assertThat(uncached.status(p.id())).isEqualTo(PlayerStatus.DISABLED);
  }
}
