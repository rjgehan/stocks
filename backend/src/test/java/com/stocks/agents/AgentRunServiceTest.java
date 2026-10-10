package com.stocks.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.ProblemField;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.agents.dto.AdminBotDto;
import com.stocks.contract.agents.dto.AgentRunDto;
import com.stocks.contract.agents.dto.AgentRunReportRequest;
import com.stocks.contract.agents.dto.AgentRunReportRequest.AgentRunTokensRequest;
import com.stocks.contract.agents.dto.AgentRunReportRequest.AgentRunToolRequest;
import com.stocks.contract.agents.dto.AgentRunStatus;
import com.stocks.contract.agents.dto.AgentRunSummaryDto;
import com.stocks.contract.agents.dto.AgentRunTrigger;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.Fixtures.PlayerRef;
import com.stocks.testkit.TestDb;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §20.6 agent runs on a real database (PostgreSQL under {@code -Ppg-it}): a report is stored for the key's player,
 * validated field by field and cleaned; a repeated {@code runRef} answers the stored run; only an AI player's key may
 * report; the public summary carries no cost; the admin Bots view computes today's spend against the cap, 14 days
 * and the 7-day tool breakdown.
 */
class AgentRunServiceTest {

  static final Instant NOW = TestTimes.START;                      // Fri 2026-10-09 10:00 ET

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;
  AgentRunService service;
  PlayerRef owner;
  PlayerRef bot;
  PlayerRef other;
  PlayerPrincipal botKey;
  PlayerPrincipal otherKey;

  @BeforeEach
  void setUp() {
    db = TestDb.fresh();
    time = TestTimes.clock();
    time.set(NOW);
    fx = new Fixtures(db.jdbc(), time);
    owner = fx.human("owner");
    bot = fx.ai("claude", owner);
    other = fx.ai("chatgpt", owner);
    botKey = key(bot);
    otherKey = key(other);
    AgentPlayers players = new AgentPlayers(db.jdbc(), mock(PlayerQueries.class));
    service = new AgentRunService(new AgentRunRepository(db.jdbc()), players, new PublicId(time), time);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  PlayerPrincipal key(PlayerRef p) {
    fx.apiKey(p, "pepper");
    long keyId = db.jdbc().sql("SELECT MAX(id) FROM api_key WHERE player_id = ?").param(p.id()).query(Long.class)
        .single();
    return TestPrincipals.apiKey(p.id(), p.publicId(), p.handle(), PlayerKind.AI, Scopes.ALL, keyId);
  }

  static AgentRunReportRequest report(String runRef, AgentRunStatus status, Instant start, String cost,
                                      String daySpent, List<AgentRunToolRequest> tools) {
    return new AgentRunReportRequest(runRef, AgentRunTrigger.SCHEDULE, "09:45", "claude-opus-5-5", status, start,
        start.plusSeconds(94), 9, 14, 1, 2, new AgentRunTokensRequest(5210L, 96000L, 12400L, 4100L),
        new BigDecimal(cost), new BigDecimal(daySpent), new BigDecimal("1.00"), "done", "1.2.0", tools);
  }

  static AgentRunReportRequest report(String runRef) {
    return report(runRef, AgentRunStatus.COMPLETED, NOW.minusSeconds(120), "0.1834", "0.1834",
        List.of(new AgentRunToolRequest("get_quotes", "OK", 3), new AgentRunToolRequest("place_order", "OK", 2),
            new AgentRunToolRequest("place_order", "RATE_LIMITED", 1),
            new AgentRunToolRequest("get_quotes", "OK", 2)));
  }

  @Test
  void aReportIsStoredForTheKeysPlayerWithItsToolCounts() {
    AgentRunService.Reported r = service.report(botKey, report("20261009T134500Z-claude-a1b2c3"));
    assertThat(r.created()).isTrue();
    AgentRunDto run = r.run();
    assertThat(run.runId()).startsWith("arn_");
    assertThat(run.player().handle()).isEqualTo("claude");
    assertThat(run.status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(run.trigger()).isEqualTo(AgentRunTrigger.SCHEDULE);
    assertThat(run.slot()).isEqualTo("09:45");
    assertThat(run.tradeDate()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(run.durationMs()).isEqualTo(94_000);
    assertThat(run.tokens().cacheRead()).isEqualTo(96_000);
    assertThat(run.costUsd()).isEqualByComparingTo("0.1834").satisfies(c -> assertThat(c.scale()).isEqualTo(6));
    assertThat(run.receivedAt()).isEqualTo(NOW);
    // duplicates of one (tool, code) are summed; sorted by tool then code
    assertThat(run.tools()).extracting(t -> t.tool() + "/" + t.code() + "=" + t.calls())
        .containsExactly("get_quotes/OK=5", "place_order/OK=2", "place_order/RATE_LIMITED=1");
  }

  @Test
  void theSameRunRefAnswersTheStoredRunWhateverTheSecondBodySays() {
    AgentRunDto first = service.report(botKey, report("ref-00000001")).run();
    AgentRunService.Reported again = service.report(botKey, report("ref-00000001", AgentRunStatus.ERROR,
        NOW.minusSeconds(60), "9.99", "9.99", List.of()));
    assertThat(again.created()).isFalse();
    assertThat(again.run().runId()).isEqualTo(first.runId());
    assertThat(again.run().status()).isEqualTo(AgentRunStatus.COMPLETED);
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM agent_run").query(Long.class).single()).isEqualTo(1);
    // another bot may use the same runRef: refs are per player
    assertThat(service.report(otherKey, report("ref-00000001")).created()).isTrue();
  }

  @Test
  void onlyAnAiPlayersKeyMayReport() {
    PlayerPrincipal humanKey = TestPrincipals.apiKey(owner.id(), owner.publicId(), "owner", PlayerKind.HUMAN,
        Scopes.ALL, 1L);
    PlayerPrincipal session = TestPrincipals.session(owner.id(), owner.publicId(), "owner");
    PlayerPrincipal botOnOauth = new PlayerPrincipal(bot.id(), bot.publicId(), "claude", PlayerKind.AI,
        PlayerRole.PLAYER, Scopes.ALL, AuthMethod.OAUTH, null, "client", NOW);
    for (PlayerPrincipal caller : List.of(humanKey, session, botOnOauth)) {
      assertThatThrownBy(() -> service.report(caller, report("ref-00000002"))).isInstanceOf(ApiException.class)
          .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void everyInvalidFieldIsListedInOneValidationError() {
    AgentRunReportRequest bad = new AgentRunReportRequest("short", null, "9:45", "", null, NOW,
        NOW.minusSeconds(1), -1, 14, 1, 2, new AgentRunTokensRequest(-5L, 0L, 0L, 2_000_000_000L),
        new BigDecimal("-0.01"), new BigDecimal("0.0000000000001"), null, null, null,
        List.of(new AgentRunToolRequest("Get Quotes", "OK", 1), new AgentRunToolRequest("get_quotes", "ok", 1),
            new AgentRunToolRequest("get_quotes", "OK", -1)));
    assertThatThrownBy(() -> service.report(botKey, bad)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
      assertThat(e.fields()).extracting(ProblemField::field).containsExactlyInAnyOrder("runRef", "trigger", "slot",
          "model", "status", "endedAt", "turns", "tokens.input", "tokens.output", "costUsd", "daySpentUsd",
          "dailyCapUsd", "tools[0].tool", "tools[1].code", "tools[2].calls");
    });
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM agent_run").query(Long.class).single()).isZero();
  }

  @Test
  void runsTooLongOrFromTheFutureAreRefused() {
    AgentRunReportRequest long7h = new AgentRunReportRequest("ref-00000003", AgentRunTrigger.ONCE, null, "gpt-5.5",
        AgentRunStatus.COMPLETED, NOW.minus(Duration.ofHours(7)), NOW, 0, 0, 0, 0, null, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ONE, null, null, null);
    AgentRunReportRequest future = new AgentRunReportRequest("ref-00000004", AgentRunTrigger.ONCE, null, "gpt-5.5",
        AgentRunStatus.COMPLETED, NOW, NOW.plus(Duration.ofMinutes(6)), 0, 0, 0, 0, null, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ONE, null, null, null);
    for (AgentRunReportRequest r : List.of(long7h, future)) {
      assertThatThrownBy(() -> service.report(botKey, r)).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.fields()).extracting(ProblemField::field).containsExactly("endedAt"));
    }
    List<AgentRunToolRequest> tooMany = new ArrayList<>();
    for (int i = 0; i < 201; i++) {
      tooMany.add(new AgentRunToolRequest("tool_" + i, "OK", 1));
    }
    assertThatThrownBy(() -> service.report(botKey, report("ref-00000005", AgentRunStatus.COMPLETED,
        NOW.minusSeconds(100), "0", "0", tooMany))).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.fields()).extracting(ProblemField::field).containsExactly("tools"));
  }

  @Test
  void theMessageLosesControlCharactersAndKeysAndIsClipped() {
    String key = "stk_i6hqouwcme6b_d-Tf233Tnb6d0PNPR_yOEHlNYu2i5eD6f531UpQ2dY8";
    assertThat(AgentRunService.cleanMessage("line one\nline\u0000 two " + key + " sk-ant-api03-abcdefghijklmnop"))
        .isEqualTo("line one line two [redacted] [redacted]");
    assertThat(AgentRunService.cleanMessage("Bearer abcdefghijklmnopqrstuvwxyz012345 failed"))
        .isEqualTo("[redacted] failed");
    assertThat(AgentRunService.cleanMessage("x".repeat(600))).hasSize(500);
    assertThat(AgentRunService.cleanMessage(" \n ")).isNull();
    assertThat(AgentRunService.cleanMessage(null)).isNull();
  }

  @Test
  void statusesAreCaseInsensitiveAndValidatedByName() {
    assertThat(AgentRunService.parseStatus("skipped_busy")).isEqualTo(AgentRunStatus.SKIPPED_BUSY);
    assertThat(AgentRunService.parseStatus(null)).isNull();
    assertThatThrownBy(() -> AgentRunService.parseStatus("nope")).isInstanceOf(ApiException.class);
  }

  @Test
  void thePublicSummaryCountsRunsWithoutCostOrMessage() {
    assertThat(service.summary("claude")).isEqualTo(new AgentRunSummaryDto("claude", 0, 0, null, null, null, null));
    service.report(botKey, report("ref-00000010", AgentRunStatus.COMPLETED, NOW.minus(Duration.ofDays(9)), "0.1",
        "0.1", List.of()));
    service.report(botKey, report("ref-00000011", AgentRunStatus.ERROR, NOW.minus(Duration.ofHours(2)), "0.2",
        "0.2", List.of()));
    AgentRunSummaryDto s = service.summary("CLAUDE");
    assertThat(s.runs()).isEqualTo(2);
    assertThat(s.runsLast7Days()).isEqualTo(1);
    assertThat(s.lastStatus()).isEqualTo(AgentRunStatus.ERROR);
    assertThat(s.lastRunAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
    assertThat(s.lastRunOrders()).isEqualTo(2);
    assertThat(s.lastRunToolCalls()).isEqualTo(14);
    assertThat(service.summary("owner").runs()).isZero();       // a human: zeros and nulls
    assertThatThrownBy(() -> service.summary("nobody")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo(ErrorCode.PLAYER_NOT_FOUND));
    fx.status(other, "DISABLED");
    assertThatThrownBy(() -> service.summary("chatgpt")).isInstanceOf(ApiException.class);
  }

  @Test
  void theAdminBotsViewShowsSpendAgainstTheCapDaysAndTools() {
    service.report(botKey, report("ref-00000020", AgentRunStatus.COMPLETED, NOW.minus(Duration.ofDays(1)), "0.3",
        "0.3", List.of(new AgentRunToolRequest("get_quotes", "OK", 4))));
    service.report(botKey, report("ref-00000021", AgentRunStatus.COMPLETED, NOW.minus(Duration.ofMinutes(30)),
        "0.5", "0.5", List.of(new AgentRunToolRequest("place_order", "OK", 1),
            new AgentRunToolRequest("place_order", "INSUFFICIENT_BUYING_POWER", 2))));
    service.report(botKey, report("ref-00000022", AgentRunStatus.DAILY_CAP, NOW.minus(Duration.ofMinutes(5)),
        "0.6", "1.1", List.of(new AgentRunToolRequest("get_quotes", "OK", 1))));
    fx.status(other, "DISABLED");

    List<AdminBotDto> bots = service.adminBots();
    assertThat(bots).extracting(b -> b.player().handle()).containsExactly("claude", "chatgpt");  // DISABLED last
    AdminBotDto claude = bots.get(0);
    assertThat(claude.lastRun().runRef()).isEqualTo("ref-00000022");
    assertThat(claude.lastProblem().status()).isEqualTo(AgentRunStatus.DAILY_CAP);
    assertThat(claude.lastRun().tools()).hasSize(1);
    assertThat(claude.today().date()).isEqualTo(LocalDate.of(2026, 10, 9));
    assertThat(claude.today().runs()).isEqualTo(2);
    assertThat(claude.today().spentUsd()).isEqualByComparingTo("1.1");
    assertThat(claude.today().capUsd()).isEqualByComparingTo("1.00");
    assertThat(claude.today().capReached()).isTrue();
    assertThat(claude.days()).hasSize(14);
    assertThat(claude.days().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 26));
    assertThat(claude.days().get(12).spentUsd()).isEqualByComparingTo("0.3");
    assertThat(claude.days().get(13).runs()).isEqualTo(2);
    assertThat(claude.days().get(5).capUsd()).isNull();
    assertThat(claude.tools()).extracting(t -> t.tool() + ":" + t.calls() + "/" + t.errors())
        .containsExactly("get_quotes:5/0", "place_order:3/2");
    assertThat(claude.tools().get(1).codes()).extracting(c -> c.code() + "=" + c.calls())
        .containsExactly("INSUFFICIENT_BUYING_POWER=2", "OK=1");
    AdminBotDto chatgpt = bots.get(1);
    assertThat(chatgpt.lastRun()).isNull();
    assertThat(chatgpt.today().capUsd()).isNull();
    assertThat(chatgpt.today().capReached()).isFalse();
  }

  @Test
  void adminRunsPageNewestFirstAndFilter() {
    for (int i = 0; i < 5; i++) {
      service.report(botKey, report("ref-0000003" + i, i == 2 ? AgentRunStatus.ERROR : AgentRunStatus.COMPLETED,
          NOW.minus(Duration.ofMinutes(10L * (5 - i))), "0.1", "0.1", List.of(new AgentRunToolRequest("whoami",
              "OK", 1))));
    }
    service.report(otherKey, report("ref-00000040"));
    Page<AgentRunDto> first = service.adminRuns(null, null, null, 4);
    assertThat(first.items()).extracting(AgentRunDto::runRef)
        .containsExactly("ref-00000040", "ref-00000034", "ref-00000033", "ref-00000032");
    assertThat(first.items().get(1).tools()).extracting(t -> t.tool()).containsExactly("whoami");
    Page<AgentRunDto> second = service.adminRuns(null, null, Cursor.parse(first.nextCursor()), 4);
    assertThat(second.items()).extracting(AgentRunDto::runRef).containsExactly("ref-00000031", "ref-00000030");
    assertThat(second.nextCursor()).isNull();
    assertThat(service.adminRuns("claude", "error", null, 10).items()).extracting(AgentRunDto::runRef)
        .containsExactly("ref-00000032");
    assertThat(service.adminRuns("chatgpt", null, null, 10).items()).hasSize(1);
    assertThatThrownBy(() -> service.adminRuns("ghost", null, null, 10)).isInstanceOf(ApiException.class);
  }
}
