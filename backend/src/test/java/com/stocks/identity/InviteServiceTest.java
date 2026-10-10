package com.stocks.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.admin.dto.InviteCreatedDto;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Invite links (§17.3, §21.1) on a fresh database (embedded PostgreSQL under -Ppg-it). */
class InviteServiceTest {

  TestDb db;
  MutableTimeSource time;
  InviteService invites;
  long admin;

  @org.junit.jupiter.api.AfterEach
  void closeDb() {
    db.close();
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    invites = new InviteService(db.jdbc(), time, new PublicId(time),
        TestProperties.stocks("stocks.public-url", "https://stocks.example.com/"));
    admin = new Fixtures(db.jdbc(), time).admin("root").id();
  }

  @Test
  void createStoresOnlyAHashAndBuildsTheJoinLink() {
    InviteCreatedDto created = invites.create(3, 48, admin);
    assertThat(created.code()).matches("^[a-z2-7]{20}$");
    assertThat(created.url()).isEqualTo("https://stocks.example.com/join/" + created.code());
    assertThat(created.expiresAt()).isEqualTo(TestTimes.START.plus(Duration.ofHours(48)));
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM invite WHERE code_hash = ?").param(created.code())
        .query(Long.class).single()).isZero();
    assertThat(invites.list()).singleElement().satisfies(i -> {
      assertThat(i.inviteId()).isEqualTo(created.inviteId());
      assertThat(i.uses()).isZero();
      assertThat(i.maxUses()).isEqualTo(3);
      assertThat(i.revokedAt()).isNull();
    });
  }

  @Test
  void codesAreCaseInsensitiveAndUsesAreCounted() {
    InviteCreatedDto created = invites.create(2, 1, admin);
    InviteService.Invite i = invites.check(" " + created.code().toUpperCase() + " ");
    invites.redeem(i);
    invites.redeem(invites.check(created.code()));
    assertThatThrownBy(() -> invites.check(created.code())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID));
    assertThatThrownBy(() -> invites.redeem(i)).isInstanceOf(ApiException.class);
    assertThat(invites.anyUsable()).isFalse();
  }

  @Test
  void expiredRevokedAndUnknownCodesAreInvalid() {
    InviteCreatedDto expiring = invites.create(5, 1, admin);
    InviteCreatedDto revoked = invites.create(5, 10, admin);
    invites.revoke(revoked.inviteId());
    invites.revoke(revoked.inviteId()); // idempotent
    assertThatThrownBy(() -> invites.check(revoked.code())).isInstanceOf(ApiException.class);
    assertThat(invites.check(expiring.code())).isNotNull();
    time.advance(Duration.ofHours(1));
    assertThatThrownBy(() -> invites.check(expiring.code())).isInstanceOf(ApiException.class);
    for (String bad : new String[] {null, "", "short", "aaaaaaaaaaaaaaaaaaaa", "AAAAAAAAAAAAAAAAAAA1"}) {
      assertThatThrownBy(() -> invites.check(bad)).as(String.valueOf(bad)).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.code()).isEqualTo(ErrorCode.INVITE_INVALID));
    }
    assertThatThrownBy(() -> invites.revoke("inv_nope")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
  }

  @Test
  void bounds() {
    for (int[] bad : new int[][] {{0, 1}, {51, 1}, {1, 0}, {1, 721}}) {
      assertThatThrownBy(() -> invites.create(bad[0], bad[1], admin)).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
    assertThat(invites.create(50, 720, admin)).isNotNull();
  }

  @Test
  void concurrentRedemptionsNeverOverspendAnInvite() throws Exception {
    InviteCreatedDto created = invites.create(3, 1, admin);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource()));
    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(() -> {
        try {
          tx.executeWithoutResult(s -> invites.redeem(invites.check(created.code())));
          return true;
        } catch (ApiException e) {
          return false;
        }
      });
    }
    ExecutorService pool = Executors.newFixedThreadPool(10);
    int ok = 0;
    for (Future<Boolean> f : pool.invokeAll(tasks)) {
      ok += f.get() ? 1 : 0;
    }
    pool.shutdown();
    assertThat(ok).isEqualTo(3);
    assertThat(db.jdbc().sql("SELECT uses FROM invite").query(Integer.class).single()).isEqualTo(3);
  }
}
