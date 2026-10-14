package com.stocks.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code V9__public_mode} (§16.7) on a database that production could hold at V8_1 (H2, or embedded PostgreSQL 18.6
 * under {@code -Ppg-it}): the feed delay goes to PT0S, deposits show in the feed, the holdings visibility row is
 * deleted, rows written with the old delay become visible, and every non-disabled human gets one SYSTEM notice.
 */
class PublicModeMigrationTest {

  static final Instant T0 = Instant.parse("2026-10-13T14:00:00Z");

  TestDb db;

  @AfterEach
  void close() {
    db.close();
  }

  @Test
  void v9MakesTheLeaguePublic() {
    db = TestDb.migratedTo("8.1");
    JdbcClient jdbc = db.jdbc();
    MutableTimeSource time = TestTimes.clock();
    time.set(T0);
    Fixtures fx = new Fixtures(jdbc, time);
    for (Map.Entry<String, String> e : Map.of("social.tradeFeedDelay", "PT15M", "social.holdingsVisibility",
        "EOD_WEIGHTS", "social.showDepositsInFeed", "false", "league.allowSelfReset", "true").entrySet()) {
      jdbc.sql("INSERT INTO app_setting (setting_key, setting_value, updated_by, updated_at) VALUES (?, ?, NULL, ?)")
          .params(e.getKey(), e.getValue(), Jdbc.ts(T0)).update();
    }
    PlayerRef alice = fx.human("alice");
    PlayerRef gone = fx.human("gone");
    fx.status(gone, "DISABLED");
    PlayerRef bot = fx.ai("claude", alice);
    Instant later = T0.plus(Duration.ofMinutes(15));
    jdbc.sql("""
            INSERT INTO feed_event (public_id, player_id, event_kind, symbol, payload, source_ref, created_at,
                                    visible_at)
            VALUES ('fde_FILL', ?, 'FILL', 'SIMA', '{}', 'EXEC:exe_1', ?, ?),
                   ('fde_NOTE', ?, 'JOURNAL', NULL, '{}', 'JNL:jnl_1', ?, ?),
                   ('fde_JOIN', ?, 'JOINED', NULL, '{}', 'JOIN:1', ?, ?)""")
        .params(bot.id(), Jdbc.ts(T0), Jdbc.ts(later), bot.id(), Jdbc.ts(T0), Jdbc.ts(later), alice.id(),
            Jdbc.ts(T0), Jdbc.ts(T0))
        .update();
    jdbc.sql("INSERT INTO journal_note (public_id, player_id, body, symbols, channel, created_at, visible_at) "
            + "VALUES ('jnl_1', ?, 'Thesis', NULL, 'MCP_KEY', ?, ?)")
        .params(bot.id(), Jdbc.ts(T0), Jdbc.ts(later)).update();

    db.migrateToLatest();

    assertThat(setting(jdbc, "social.tradeFeedDelay")).containsExactly("PT0S");
    assertThat(setting(jdbc, "social.showDepositsInFeed")).containsExactly("true");
    assertThat(setting(jdbc, "social.holdingsVisibility")).isEmpty();
    assertThat(setting(jdbc, "league.allowSelfReset")).as("other settings untouched").containsExactly("true");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM app_setting WHERE setting_key = 'social.tradeFeedDelay' "
        + "AND updated_by IS NULL").query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM feed_event WHERE visible_at <> created_at").query(Long.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_note WHERE visible_at <> created_at")
        .query(Long.class).single()).isZero();
    List<Map<String, Object>> notices = jdbc.sql("SELECT public_id, player_id, kind, title, body, source_ref, read_at "
        + "FROM notification").query().listOfRows();
    assertThat(notices).singleElement().satisfies(n -> {
      assertThat(n.get("public_id")).asString().matches("^ntf_[0-9A-HJKMNP-TV-Z]{26}$")
          .isEqualTo("ntf_" + alice.publicId().substring(4));
      assertThat(((Number) n.get("player_id")).longValue()).isEqualTo(alice.id());
      assertThat(n.get("kind")).isEqualTo("SYSTEM");
      assertThat(n.get("title")).isEqualTo("The league is now public");
      assertThat(n.get("body")).asString().contains("visible to everyone, live")
          .contains("keep the visibility you chose");
      assertThat(n.get("source_ref")).isEqualTo("PUBLIC_MODE");
      assertThat(n.get("read_at")).isNull();
    });
  }

  @Test
  void onAFreshDatabaseV9TouchesNothing() {
    db = TestDb.migratedTo("8.1");
    db.migrateToLatest();
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM app_setting").query(Long.class).single()).isZero();
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM notification").query(Long.class).single()).isZero();
  }

  static List<String> setting(JdbcClient jdbc, String key) {
    return jdbc.sql("SELECT setting_value FROM app_setting WHERE setting_key = ?").param(key).query(String.class)
        .list();
  }
}
