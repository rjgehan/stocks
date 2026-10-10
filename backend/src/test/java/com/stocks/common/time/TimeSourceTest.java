package com.stocks.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TimeSourceTest {

  @Test
  void mutableTimeSourceOnlyMovesWhenTold() {
    MutableTimeSource t = new MutableTimeSource(Instant.parse("2026-10-09T14:00:00.123456789Z"));
    assertThat(t.now()).isEqualTo(Instant.parse("2026-10-09T14:00:00.123456Z")); // truncated to micros
    t.advance(Duration.ofMinutes(16));
    assertThat(t.now()).isEqualTo(Instant.parse("2026-10-09T14:16:00.123456Z"));
    assertThat(t.clock().instant()).isEqualTo(t.now());
    assertThat(t.clock().getZone().getId()).isEqualTo("Z");
    assertThatThrownBy(() -> t.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    t.set(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(t.now()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void todayEtUsesNewYorkNotUtc() {
    // 2026-10-10T02:00Z is still Friday 2026-10-09 in New York (EDT, UTC-4)
    MutableTimeSource t = new MutableTimeSource(Instant.parse("2026-10-10T02:00:00Z"));
    assertThat(t.todayEt()).isEqualTo(LocalDate.of(2026, 10, 9));
    t.advance(Duration.ofHours(2));
    assertThat(t.todayEt()).isEqualTo(LocalDate.of(2026, 10, 10));
  }

  @Test
  void offsetTimeSourceStartsAtTheAnchorAndAdvancesWithTheBase() {
    MutableTimeSource wall = new MutableTimeSource(Instant.parse("2030-01-01T00:00:00Z"));
    OffsetTimeSource sim = OffsetTimeSource.anchoredAt(wall, Instant.parse("2026-10-09T14:00:00Z"));
    assertThat(sim.now()).isEqualTo(Instant.parse("2026-10-09T14:00:00Z"));
    wall.advance(Duration.ofSeconds(30));
    assertThat(sim.now()).isEqualTo(Instant.parse("2026-10-09T14:00:30Z"));
    sim.advance(Duration.ofMinutes(16));
    assertThat(sim.now()).isEqualTo(Instant.parse("2026-10-09T14:16:30Z"));
    assertThat(sim.clock().instant()).isEqualTo(sim.now());
  }

  @Test
  void systemTimeSourceTicksInMicroseconds() {
    SystemTimeSource t = new SystemTimeSource();
    Instant now = t.now();
    assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    assertThat(Duration.between(now, t.clock().instant()).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void etHelpersHandleDst() {
    // 09:30 ET on Fri 2026-10-09 (EDT) and on Mon 2026-11-02 (EST, after the 2026-11-01 fall-back)
    assertThat(Et.at(LocalDate.of(2026, 10, 9), LocalTime.of(9, 30))).isEqualTo(Instant.parse("2026-10-09T13:30:00Z"));
    assertThat(Et.at(LocalDate.of(2026, 11, 2), LocalTime.of(9, 30))).isEqualTo(Instant.parse("2026-11-02T14:30:00Z"));
    assertThat(Et.startOfDay(LocalDate.of(2026, 10, 9))).isEqualTo(Instant.parse("2026-10-09T04:00:00Z"));
    assertThat(Et.date(Instant.parse("2026-10-10T03:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(Et.time(Instant.parse("2026-10-09T20:00:00Z"))).isEqualTo(LocalTime.of(16, 0));
    assertThat(Et.zoned(Instant.parse("2026-10-09T20:00:00Z")).getZone()).isEqualTo(TimeSource.ET);
  }
}
