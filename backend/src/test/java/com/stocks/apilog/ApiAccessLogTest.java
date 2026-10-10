package com.stocks.apilog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.ApiCallDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §17.10 the API access log on a real database (the UPDATE-or-INSERT with GREATEST/LEAST and nullable IP/client
 * runs on PostgreSQL under {@code -Ppg-it}): hourly aggregation per (key, channel, endpoint, outcome), only API keys,
 * the pending-bucket bound, the Recent API calls views (own keys and owned bots; the admin sees all) and retention.
 */
class ApiAccessLogTest {

  static final Instant T0 = Instant.parse("2026-10-09T14:05:00Z");

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;
  ApiAccessLogRepository repository;
  ApiAccessLog log;
  ApiCallService calls;
  PlayerRef owner;
  PlayerRef bot;
  PlayerRef stranger;
  PlayerPrincipal botKey;
  PlayerPrincipal ownerKey;
  PlayerPrincipal strangerKey;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    time.set(T0);
    fx = new Fixtures(db.jdbc(), time);
    owner = fx.human("owner");
    bot = fx.ai("claude", owner);
    stranger = fx.human("stranger");
    botKey = key(bot, PlayerKind.AI);
    ownerKey = key(owner, PlayerKind.HUMAN);
    strangerKey = key(stranger, PlayerKind.HUMAN);
    repository = new ApiAccessLogRepository(db.jdbc());
    log = new ApiAccessLog(repository, time, ApiAccessLogProperties.defaults());
    calls = new ApiCallService(log, repository, db.jdbc());
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  PlayerPrincipal key(PlayerRef p, PlayerKind kind) {
    fx.apiKey(p, "pepper");
    long keyId = db.jdbc().sql("SELECT MAX(id) FROM api_key WHERE player_id = ?").param(p.id()).query(Long.class)
        .single();
    return TestPrincipals.apiKey(p.id(), p.publicId(), p.handle(), kind, Scopes.ALL, keyId);
  }

  List<Map<String, Object>> rows() {
    return db.jdbc().sql("SELECT * FROM api_access_log ORDER BY id").query().listOfRows();
  }

  @Test
  void callsAreCountedPerHourKeyChannelEndpointAndOutcome() {
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 12, "10.0.0.1", "agent-runner/claude");
    time.set(T0.plusSeconds(30));
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 40, "10.0.0.2", "agent-runner/claude");
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "RATE_LIMITED", 1, null, null);
    log.record(botKey, Channel.REST_KEY, "GET /api/v1/account", "200", 5, "10.0.0.1", "curl/8");
    assertThat(log.pendingBuckets()).isEqualTo(3);
    assertThat(log.flush()).isEqualTo(3);
    assertThat(log.pendingBuckets()).isZero();

    // the next flush adds to the same row: counts summed, max kept, first/last widened, last IP/client replaced
    time.set(T0.plus(Duration.ofMinutes(20)));
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 30, "10.0.0.3", null);
    log.flush();
    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(3);
    Map<String, Object> quotes = rows.stream()
        .filter(r -> "get_quotes".equals(r.get("endpoint")) && "OK".equals(r.get("outcome"))).findFirst().orElseThrow();
    assertThat(((Number) quotes.get("calls")).intValue()).isEqualTo(3);
    assertThat(((Number) quotes.get("total_ms")).longValue()).isEqualTo(82);
    assertThat(((Number) quotes.get("max_ms")).intValue()).isEqualTo(40);
    assertThat(quotes.get("last_ip")).isEqualTo("10.0.0.3");
    assertThat(quotes.get("last_client")).isNull();
    assertThat(quotes.get("key_ref")).isNotNull();
    Instant first = db.jdbc().sql("SELECT first_at, last_at FROM api_access_log WHERE id = ?")
        .param(quotes.get("id")).query((rs, n) -> Jdbc.instant(rs, "first_at")).single();
    assertThat(first).isEqualTo(T0);

    // a new UTC hour is a new bucket
    time.set(Instant.parse("2026-10-09T15:00:00Z"));
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 1, null, null);
    log.flush();
    assertThat(rows()).hasSize(4);
  }

  @Test
  void onlyApiKeysAreRecorded() {
    log.record(TestPrincipals.session(owner.id(), owner.publicId(), "owner"), Channel.WEB, "GET /api/v1/account",
        "200", 1, null, null);
    log.record(null, Channel.REST_KEY, "GET /api/v1/account", "401", 1, null, null);
    assertThat(log.pendingBuckets()).isZero();
  }

  @Test
  void bucketsBeyondTheBoundAreDroppedButExistingOnesStillCount() {
    ApiAccessLog small = new ApiAccessLog(repository, time, new ApiAccessLogProperties(true,
        Duration.ofSeconds(15), 2, 90));
    small.record(botKey, Channel.MCP_KEY, "a_tool", "OK", 1, null, null);
    small.record(botKey, Channel.MCP_KEY, "b_tool", "OK", 1, null, null);
    small.record(botKey, Channel.MCP_KEY, "c_tool", "OK", 1, null, null);     // dropped
    small.record(botKey, Channel.MCP_KEY, "a_tool", "OK", 1, null, null);     // counted
    assertThat(small.pendingBuckets()).isEqualTo(2);
    small.flush();
    assertThat(rows()).extracting(r -> r.get("endpoint") + "=" + r.get("calls")).containsExactlyInAnyOrder(
        "a_tool=2", "b_tool=1");
  }

  @Test
  void anOffSwitchRecordsNothingAndTheViewsAreEmpty() {
    ApiAccessLog off = new ApiAccessLog(repository, time, new ApiAccessLogProperties(false, Duration.ofSeconds(15),
        10, 90));
    off.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 1, null, null);
    assertThat(off.pendingBuckets()).isZero();
    assertThat(new ApiCallService(off, repository, db.jdbc()).all(null, null, 50).items()).isEmpty();
  }

  @Test
  void playersSeeTheirOwnKeysAndTheirBotsAndTheAdminSeesEveryone() {
    log.record(botKey, Channel.MCP_KEY, "place_order", "OK", 10, "10.0.0.1", "agent-runner/claude");
    time.set(T0.plusSeconds(1));
    log.record(ownerKey, Channel.REST_KEY, "GET /api/v1/orders/{orderId}", "404", 3, "10.0.0.9", "curl/8");
    time.set(T0.plusSeconds(2));
    log.record(strangerKey, Channel.REST_KEY, "GET /api/v1/account", "200", 3, "10.0.0.7", "python");

    Page<ApiCallDto> mine = calls.mine(owner.id(), null, null, 50);       // flushes first
    assertThat(mine.items()).extracting(ApiCallDto::handle).containsExactly("owner", "claude");
    ApiCallDto order = mine.items().get(0);
    assertThat(order.ok()).isFalse();
    assertThat(order.outcome()).isEqualTo("404");
    assertThat(order.channel()).isEqualTo(Channel.REST_KEY);
    assertThat(order.keyName()).isEqualTo("fixture");
    assertThat(order.keyId()).hasSize(12);
    assertThat(order.hourStart()).isEqualTo(Instant.parse("2026-10-09T14:00:00Z"));
    assertThat(order.client()).isEqualTo("curl/8");
    ApiCallDto bots = mine.items().get(1);
    assertThat(bots.ok()).isTrue();
    assertThat(bots.playerKind()).isEqualTo(PlayerKind.AI);
    assertThat(bots.avgMs()).isEqualTo(10);

    assertThat(calls.mine(owner.id(), "CLAUDE", null, 50).items()).extracting(ApiCallDto::endpoint)
        .containsExactly("place_order");
    assertThatThrownBy(() -> calls.mine(owner.id(), "stranger", null, 50)).isInstanceOfSatisfying(
        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    assertThat(calls.mine(stranger.id(), null, null, 50).items()).extracting(ApiCallDto::handle)
        .containsExactly("stranger");
    assertThatThrownBy(() -> calls.mine(stranger.id(), "claude", null, 50)).isInstanceOf(ApiException.class);

    Page<ApiCallDto> all = calls.all(null, null, 2);
    assertThat(all.items()).extracting(ApiCallDto::handle).containsExactly("stranger", "owner");
    assertThat(calls.all(null, Cursor.parse(all.nextCursor()), 2).items()).extracting(ApiCallDto::handle)
        .containsExactly("claude");
    assertThat(calls.all("claude", null, 50).items()).hasSize(1);
    assertThatThrownBy(() -> calls.all("ghost", null, 50)).isInstanceOf(ApiException.class);
  }

  @Test
  void housekeepingDeletesBucketsOlderThanTheRetention() {
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 1, null, null);
    log.flush();
    time.set(T0.plus(Duration.ofDays(91)));
    log.record(botKey, Channel.MCP_KEY, "get_quotes", "OK", 1, null, null);
    log.flush();
    assertThat(repository.deleteOlderThan(time.now().minus(Duration.ofDays(90)))).isEqualTo(1);
    assertThat(rows()).hasSize(1);
  }
}
