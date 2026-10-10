package com.stocks.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.admin.dto.AuditRowDto;
import com.stocks.contract.security.RequestMeta;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code GET /admin/audit} reads (§17.7, §21.1) on a fresh database (embedded PostgreSQL under -Ppg-it). */
class AuditQueriesTest {

  TestDb db;
  MutableTimeSource time;
  AuditService audit;
  AuditQueries queries;
  Fixtures fx;

  @org.junit.jupiter.api.AfterEach
  void closeDb() {
    db.close();
  }

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    audit = new AuditService(db.jdbc(), time, JacksonConfig.newMapper());
    queries = new AuditQueries(db.jdbc(), JacksonConfig.newMapper());
    fx = new Fixtures(db.jdbc(), time);
  }

  @Test
  void filtersJoinAndPageNewestFirst() {
    Fixtures.PlayerRef admin = fx.admin("root");
    Fixtures.PlayerRef bot = fx.ai("claude", admin);
    String key = fx.apiKey(bot, "pepper-0123456789");
    long keyRowId = db.jdbc().sql("SELECT id FROM api_key WHERE player_id = ?").param(bot.id()).query(Long.class)
        .single();
    RequestMeta meta = new RequestMeta("req_1", Channel.ADMIN, "10.0.0.1", "curl", null, null);
    for (int i = 0; i < 5; i++) {
      audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_UPDATE", AuditOutcome.OK)
          .withActor(admin.id(), PlayerKind.HUMAN).withMeta(meta).withTarget("player", bot.publicId())
          .withDetail("i", i).withDetail("password", "hunter2"));
      time.advance(Duration.ofSeconds(1));
    }
    audit.record(new AuditEntry(AuditCategory.ORDER, "ORDER_REJECT", AuditOutcome.REJECTED, bot.id(), PlayerKind.AI,
        keyRowId, null, new RequestMeta("req_2", Channel.MCP_KEY, null, null, "claude-code", "place_order"), "order",
        "ord_1", java.util.Map.of("code", "INSUFFICIENT_BUYING_POWER")));

    Page<AuditRowDto> all = queries.list(AuditQueries.Filter.none(), null, 50);
    assertThat(all.items()).hasSize(6);
    AuditRowDto newest = all.items().get(0);
    assertThat(newest.action()).isEqualTo("ORDER_REJECT");
    assertThat(newest.actor()).isEqualTo("claude");
    assertThat(newest.actorKind()).isEqualTo(PlayerKind.AI);
    assertThat(newest.channel()).isEqualTo(Channel.MCP_KEY);
    assertThat(newest.apiKeyId()).isEqualTo(key.substring(4, 16));
    assertThat(newest.mcpTool()).isEqualTo("place_order");
    assertThat(newest.detail()).containsEntry("code", "INSUFFICIENT_BUYING_POWER");

    Page<AuditRowDto> p1 = queries.list(new AuditQueries.Filter(AuditCategory.ADMIN, "player_update", admin.id(),
        null, null), null, 2);
    assertThat(p1.items()).extracting(r -> r.detail().get("i")).containsExactly(4, 3);
    assertThat(p1.items().get(0).detail()).containsEntry("password", "***");
    assertThat(p1.items().get(0).ip()).isEqualTo("10.0.0.1");
    Page<AuditRowDto> p2 = queries.list(new AuditQueries.Filter(AuditCategory.ADMIN, null, admin.id(), null, null),
        Cursor.parse(p1.nextCursor()), 2);
    assertThat(p2.items()).extracting(r -> r.detail().get("i")).containsExactly(2, 1);
    Page<AuditRowDto> p3 = queries.list(new AuditQueries.Filter(AuditCategory.ADMIN, null, admin.id(), null, null),
        Cursor.parse(p2.nextCursor()), 2);
    assertThat(p3.items()).hasSize(1);
    assertThat(p3.nextCursor()).isNull();

    Page<AuditRowDto> window = queries.list(new AuditQueries.Filter(null, null, null,
        TestTimes.START.plusSeconds(1), TestTimes.START.plusSeconds(3)), null, 50);
    assertThat(window.items()).hasSize(2);

    List<AuditRowDto> forBot = queries.recentFor(bot.id(), bot.publicId(), bot.handle(), 3);
    assertThat(forBot).hasSize(3);
    assertThat(forBot.get(0).action()).isEqualTo("ORDER_REJECT");
    assertThat(AuditQueries.category(null)).isNull();
    assertThat(AuditQueries.category("money")).isEqualTo(AuditCategory.MONEY);
    assertThatThrownBy(() -> AuditQueries.category("nope")).isInstanceOf(ApiException.class);
  }
}
