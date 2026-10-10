package com.stocks.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.time.MutableTimeSource;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PublicIdTest {

  private static final Pattern FORMAT = Pattern.compile("^[a-z]{3}_[0-7][0-9A-HJKMNP-TV-Z]{25}$");

  private final MutableTimeSource time = new MutableTimeSource(Instant.parse("2026-10-09T14:00:00Z"));
  private final PublicId ids = new PublicId(time, new SplittableRandom(7));

  @Test
  void generatesPrefixedUlidsFromTheTimeSource() {
    String id = ids.next(Prefix.ORDER);
    assertThat(id).startsWith("ord_").hasSize(PublicId.LENGTH).hasSizeLessThanOrEqualTo(32).matches(FORMAT);
    assertThat(Ulid.timestamp(id.substring(4))).isEqualTo(time.now());

    time.advance(Duration.ofMinutes(16));
    assertThat(Ulid.timestamp(ids.next(Prefix.PLAYER).substring(4))).isEqualTo(Instant.parse("2026-10-09T14:16:00Z"));
  }

  @Test
  void everyPrefixFromTheSpecTable() {
    assertThat(Prefix.values()).extracting(Prefix::code).containsExactly(
        "ply", "acc", "ord", "exe", "vio", "fde", "led", "xfr", "bat", "inv", "wlt", "cac", "ntf", "aev", "alr", "psh",
        "req");
    for (Prefix p : Prefix.values()) {
      String id = ids.next(p);
      assertThat(id).matches(FORMAT);
      assertThat(PublicId.isValid(id, p)).isTrue();
      assertThat(PublicId.prefixOf(id)).contains(p);
      assertThat(Prefix.fromCode(p.code())).isEqualTo(p);
    }
  }

  @Test
  void idsAreUniqueAndSortByCreation() {
    Set<String> seen = new HashSet<>();
    String previous = "";
    for (int i = 0; i < 5_000; i++) {
      if (i % 100 == 0) {
        time.advance(Duration.ofMillis(1));
      }
      String id = ids.next(Prefix.EXECUTION);
      assertThat(seen.add(id)).isTrue();
      assertThat(id).isGreaterThan(previous);
      previous = id;
    }
  }

  @Test
  void seededGeneratorsAreReproducible() {
    PublicId a = new PublicId(new MutableTimeSource(Instant.EPOCH.plusSeconds(1)), new SplittableRandom(99));
    PublicId b = new PublicId(new MutableTimeSource(Instant.EPOCH.plusSeconds(1)), new SplittableRandom(99));
    assertThat(a.next(Prefix.LEDGER)).isEqualTo(b.next(Prefix.LEDGER));
  }

  @Test
  void rejectsMalformedIds() {
    String good = ids.next(Prefix.ACCOUNT);
    assertThat(PublicId.isValid(good, Prefix.ORDER)).isFalse();
    assertThat(PublicId.isValid(good + "X", Prefix.ACCOUNT)).isFalse();
    assertThat(PublicId.isValid(null, Prefix.ACCOUNT)).isFalse();
    assertThat(PublicId.prefixOf("zzz_" + good.substring(4))).isEmpty();
    assertThat(PublicId.prefixOf("acc-" + good.substring(4))).isEmpty();
    assertThat(PublicId.prefixOf(null)).isEmpty();
  }
}
