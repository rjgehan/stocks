package com.stocks.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class UlidTest {

  @Test
  void encodesTimestampAndRandomnessInCrockfordBase32() {
    // Reference vector: all-zero randomness, timestamp 1469918176385 → "01ARYZ6S41" (ulid/spec README)
    String ulid = Ulid.encode(1469918176385L, 0, 0);
    assertThat(ulid).hasSize(26).startsWith("01ARYZ6S41").endsWith("0000000000000000");
    assertThat(Ulid.encode(0, 0, 0)).isEqualTo("00000000000000000000000000");
    assertThat(Ulid.encode(Ulid.MAX_TIMESTAMP, 0xFFFF, -1L)).isEqualTo("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
  }

  @Test
  void decodesTheTimestamp() {
    Instant t = Instant.parse("2026-10-09T14:00:00.123Z");
    String ulid = new Ulid.Generator(new SplittableRandom(1)).next(t.toEpochMilli());
    assertThat(Ulid.isValid(ulid)).isTrue();
    assertThat(Ulid.timestamp(ulid)).isEqualTo(t);
    assertThat(Ulid.timestamp(ulid.toLowerCase())).isEqualTo(t);
  }

  @Test
  void validatesSyntax() {
    assertThat(Ulid.isValid("01ARYZ6S41TSV4RRFFQ69G5FAV")).isTrue();
    assertThat(Ulid.isValid("01ARYZ6S41TSV4RRFFQ69G5FA")).isFalse();   // 25 chars
    assertThat(Ulid.isValid("01ARYZ6S41TSV4RRFFQ69G5FAU")).isFalse();  // U is not Crockford
    assertThat(Ulid.isValid("81ARYZ6S41TSV4RRFFQ69G5FAV")).isFalse();  // timestamp overflow
    assertThat(Ulid.isValid(null)).isFalse();
    assertThatThrownBy(() -> Ulid.timestamp("nope")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Ulid.encode(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isMonotonicWithinOneMillisecondAndWhenTheClockStepsBack() {
    Ulid.Generator gen = new Ulid.Generator(new SplittableRandom(42));
    List<String> ids = new ArrayList<>();
    long t = 1_790_000_000_000L;
    for (int i = 0; i < 1_000; i++) {
      ids.add(gen.next(t));                  // same millisecond
    }
    ids.add(gen.next(t - 5));                // clock stepped back
    ids.add(gen.next(t + 1));
    for (int i = 1; i < ids.size(); i++) {
      assertThat(ids.get(i)).isGreaterThan(ids.get(i - 1));
    }
    assertThat(Ulid.timestamp(ids.get(1_000))).isEqualTo(Instant.ofEpochMilli(t));
  }

  @Test
  void carriesIntoTheHighBitsOnOverflow() {
    // A generator whose first random draw is (hi=0x0000, lo=-1): the next id in the same ms must carry.
    Ulid.Generator gen = new Ulid.Generator(new java.util.random.RandomGenerator() {
      private int calls;

      @Override
      public long nextLong() {
        return calls++ == 0 ? 0L : -1L;
      }
    });
    String a = gen.next(1000);
    String b = gen.next(1000);
    assertThat(b).isGreaterThan(a);
    assertThat(a.substring(10)).isEqualTo("000FZZZZZZZZZZZZ");
    assertThat(b.substring(10)).isEqualTo("000G000000000000");
  }
}
