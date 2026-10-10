package com.stocks.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.security.RateLimitStatus;
import com.stocks.testkit.TestProperties;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §17.5 buckets with the production defaults, driven by the test clock. */
class RateLimitServiceImplTest {

  MutableTimeSource time;
  RateLimitServiceImpl limits;

  @BeforeEach
  void setUp() {
    time = TestTimes.clock();
    limits = new RateLimitServiceImpl(TestProperties.stocks(), time);
  }

  @Test
  void orderWritesAre20PerMinuteAnd300PerDayPerPlayer() {
    for (int i = 0; i < 20; i++) {
      limits.consumeWrite(1);
    }
    assertThatThrownBy(() -> limits.consumeWrite(1)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
      assertThat(e.status()).isEqualTo(429);
      assertThat(e.retryAfterS()).isEqualTo(3L); // 20/min refills one token every 3 s
    });
    limits.consumeWrite(2); // another player has its own bucket
    RateLimitStatus s = limits.remaining(1);
    assertThat(s.writesRemainingMinute()).isZero();
    assertThat(s.writesRemainingDay()).isEqualTo(280);
    assertThat(s.readsRemaining()).isEqualTo(30);

    limits.refundWrite(1); // an idempotent replay detected on the engine thread is free
    assertThat(limits.remaining(1).writesRemainingMinute()).isEqualTo(1);
    assertThat(limits.remaining(1).writesRemainingDay()).isEqualTo(281);
    limits.consumeWrite(1);
    time.advance(Duration.ofSeconds(3));
    limits.consumeWrite(1);
  }

  @Test
  void theDailyWriteLimitGivesTheMinuteTokenBack() {
    RateLimitServiceImpl fast = new RateLimitServiceImpl(
        TestProperties.stocks("stocks.ratelimit.writes-per-min", "1000"), time);
    for (int i = 0; i < 300; i++) {
      fast.consumeWrite(3);
    }
    assertThatThrownBy(() -> fast.consumeWrite(3)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.detail()).contains("300 per day");
      assertThat(e.retryAfterS()).isEqualTo(288L); // 300/day refills one token every 288 s
    });
    assertThat(fast.remaining(3).writesRemainingMinute()).isEqualTo(700);
    assertThat(fast.remaining(3).writesRemainingDay()).isZero();
  }

  @Test
  void keyReadsHaveABurstOf30AndRefillAt2PerSecond() {
    for (int i = 0; i < 30; i++) {
      assertThat(limits.consumeRead(7, true, RateLimitServiceImpl.ReadClass.READ).allowed()).isTrue();
    }
    RateLimitServiceImpl.Decision denied = limits.consumeRead(7, true, RateLimitServiceImpl.ReadClass.READ);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.limit()).isEqualTo(120);
    assertThat(denied.retryAfterSeconds()).isEqualTo(1);
    assertThat(limits.consumeRead(7, false, RateLimitServiceImpl.ReadClass.READ).allowed())
        .as("the web bucket is separate").isTrue();
    time.advance(Duration.ofSeconds(1));
    assertThat(limits.consumeRead(7, true, RateLimitServiceImpl.ReadClass.READ).remaining()).isEqualTo(1);
  }

  @Test
  void heavyReadsAndIpBuckets() {
    for (int i = 0; i < 30; i++) {
      limits.consumeRead(9, true, RateLimitServiceImpl.ReadClass.HEAVY);
    }
    assertThat(limits.consumeRead(9, true, RateLimitServiceImpl.ReadClass.HEAVY).allowed()).isFalse();
    for (int i = 0; i < 10; i++) {
      assertThat(limits.consumeIp("1.2.3.4", RateLimitServiceImpl.IpClass.LOGIN).allowed()).isTrue();
    }
    RateLimitServiceImpl.Decision login = limits.consumeIp("1.2.3.4", RateLimitServiceImpl.IpClass.LOGIN);
    assertThat(login.allowed()).isFalse();
    assertThat(login.resetSeconds()).isEqualTo(60);
    for (int i = 0; i < 5; i++) {
      limits.consumeIp("1.2.3.4", RateLimitServiceImpl.IpClass.SIGNUP);
    }
    assertThat(limits.consumeIp("1.2.3.4", RateLimitServiceImpl.IpClass.SIGNUP).retryAfterSeconds()).isEqualTo(720);
    assertThat(limits.consumeIp("5.6.7.8", RateLimitServiceImpl.IpClass.LOGIN).allowed()).isTrue();
    assertThat(limits.consumeIp(null, RateLimitServiceImpl.IpClass.ANONYMOUS).allowed()).isTrue();
  }

  @Test
  void ceilSeconds() {
    assertThat(RateLimitServiceImpl.ceilSeconds(0)).isZero();
    assertThat(RateLimitServiceImpl.ceilSeconds(1)).isEqualTo(1);
    assertThat(RateLimitServiceImpl.ceilSeconds(1_000_000_000L)).isEqualTo(1);
    assertThat(RateLimitServiceImpl.ceilSeconds(1_000_000_001L)).isEqualTo(2);
  }
}
