package com.stocks.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stocks.audit.AuditService;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.agents.dto.FinishRunRequest;
import com.stocks.contract.agents.dto.RunNowRequest;
import com.stocks.contract.agents.dto.RunRequestDto;
import com.stocks.contract.agents.dto.RunRequestListDto;
import com.stocks.contract.agents.dto.RunRequestStatus;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * §20.9 "Run now" on a real database (PostgreSQL under {@code -Ppg-it}): the owner or an ACTIVE admin asks, the bot's
 * own key claims and finishes; one open request per bot (also against a race, by the table's constraint), 20 per bot
 * in 24 hours, lazy expiry of unclaimed and unreported requests, and what the list tells the button (the bot's status,
 * when a trader last asked).
 */
class RunRequestServiceTest {

  static final Instant NOW = TestTimes.START;                      // Fri 2026-10-09 10:00 ET

  TestDb db;
  MutableTimeSource time;
  Fixtures fx;
  RunRequestRepository repository;
  RunRequestService service;
  PlayerRef owner;
  PlayerRef bot;
  PlayerRef other;
  PlayerRef stranger;
  PlayerRef admin;
  PlayerPrincipal ownerSession;
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
    stranger = fx.human("stranger");
    admin = fx.admin("root");
    ownerSession = TestPrincipals.session(owner.id(), owner.publicId(), owner.handle());
    botKey = key(bot);
    otherKey = key(other);
    repository = new RunRequestRepository(db.jdbc());
    service = new RunRequestService(repository, new AgentPlayers(db.jdbc(), mock(PlayerQueries.class)),
        new PublicId(time), time, new AuditService(db.jdbc(), time, JacksonConfig.newMapper()),
        AgentsProperties.defaults());
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

  RunRequestDto ask(PlayerPrincipal caller, String handle, boolean dryRun) {
    return service.request(caller, handle, new RunNowRequest(dryRun), RequestMeta.system(null));
  }

  List<RunRequestDto> listed(PlayerPrincipal caller, String handle, Integer limit) {
    return service.list(caller, handle, limit).requests();
  }

  static void fails(ThrowingCallable call, ErrorCode code, String detailPart) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(code);
      assertThat(e.detail()).contains(detailPart);
    });
  }

  @Test
  void theOwnerAsksTheBotsKeyClaimsAndFinishes() {
    RunRequestDto asked = ask(ownerSession, "Claude", true);
    assertThat(asked.id()).startsWith("arq_");
    assertThat(asked.handle()).isEqualTo("claude");
    assertThat(asked.requestedBy()).isEqualTo("owner");
    assertThat(asked.requestedAt()).isEqualTo(NOW);
    assertThat(asked.dryRun()).isTrue();
    assertThat(asked.status()).isEqualTo(RunRequestStatus.PENDING);
    assertThat(asked.claimedAt()).isNull();
    List<Map<String, Object>> audit = db.jdbc().sql("SELECT * FROM audit_log WHERE action = 'BOT_RUN_REQUEST'")
        .query().listOfRows();
    assertThat(audit).singleElement().satisfies(row -> {
      assertThat(row.get("actor_player_id")).isEqualTo(owner.id());
      assertThat(row.get("category")).isEqualTo("ACCOUNT");
      assertThat(row.get("outcome")).isEqualTo("OK");
      assertThat(row.get("target_id")).isEqualTo("claude");
      assertThat((String) row.get("detail")).contains(asked.id()).contains("\"dryRun\":true");
    });

    assertThat(service.claim(otherKey)).isEmpty();                 // another bot's key sees only its own requests
    time.advance(Duration.ofSeconds(40));
    RunRequestDto claimed = service.claim(botKey).orElseThrow();
    assertThat(claimed.id()).isEqualTo(asked.id());
    assertThat(claimed.status()).isEqualTo(RunRequestStatus.CLAIMED);
    assertThat(claimed.claimedAt()).isEqualTo(NOW.plusSeconds(40));
    assertThat(claimed.dryRun()).isTrue();
    assertThat(service.claim(botKey)).isEmpty();                   // claimed once

    time.advance(Duration.ofMinutes(4));
    RunRequestDto done = service.finish(botKey, asked.id(), new FinishRunRequest(RunRequestStatus.DONE,
        "Check-in done in 4 min 2 s (12 turns)."));
    assertThat(done.status()).isEqualTo(RunRequestStatus.DONE);
    assertThat(done.finishedAt()).isEqualTo(NOW.plusSeconds(40).plus(Duration.ofMinutes(4)));
    assertThat(done.message()).isEqualTo("Check-in done in 4 min 2 s (12 turns).");
    // a retried report answers the stored request; another final status is a conflict
    assertThat(service.finish(botKey, asked.id(), new FinishRunRequest(RunRequestStatus.DONE, "again")))
        .isEqualTo(done);
    fails(() -> service.finish(botKey, asked.id(), new FinishRunRequest(RunRequestStatus.FAILED, "late")),
        ErrorCode.RUN_REQUEST_CONFLICT, "The run request is DONE");

    time.advance(Duration.ofMinutes(1));
    RunRequestDto second = service.request(ownerSession, "claude", null, RequestMeta.system(null));  // no body
    assertThat(second.dryRun()).isFalse();
    assertThat(listed(ownerSession, "claude", null)).extracting(RunRequestDto::id)
        .containsExactly(second.id(), asked.id());
    assertThat(listed(ownerSession, "claude", 1)).extracting(RunRequestDto::id).containsExactly(second.id());
  }

  @Test
  void aBotHasOneOpenRequestAtATime() {
    ask(ownerSession, "claude", false);
    fails(() -> ask(ownerSession, "claude", true), ErrorCode.RUN_REQUEST_CONFLICT,
        "@claude already has a run request waiting for the trader (requested at 10:00 ET by @owner)");
    ask(ownerSession, "chatgpt", false);                           // the other bot is not held up
    time.advance(Duration.ofMinutes(1));
    RunRequestDto claimed = service.claim(botKey).orElseThrow();
    fails(() -> ask(ownerSession, "claude", false), ErrorCode.RUN_REQUEST_CONFLICT,
        "@claude is already running a requested check-in (picked up at 10:01 ET)");
    service.finish(botKey, claimed.id(), new FinishRunRequest(RunRequestStatus.FAILED, "claude exited 1"));
    assertThat(ask(ownerSession, "claude", false).status()).isEqualTo(RunRequestStatus.PENDING);
    // two requests racing past the check: the open-request constraint lets one in
    long chatgpt = other.id();
    assertThatThrownBy(() -> repository.insert(new PublicId(time).next(Prefix.AGENT_RUN_REQUEST), chatgpt,
        owner.id(), time.now(), false)).isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void onlyTheOwnerOrAnActiveAdminRunsAnActiveBot() {
    PlayerPrincipal strangerSession = TestPrincipals.session(stranger.id(), stranger.publicId(), stranger.handle());
    PlayerPrincipal adminSession = TestPrincipals.adminSession(admin.id(), admin.publicId(), admin.handle());
    fails(() -> ask(strangerSession, "claude", false), ErrorCode.FORBIDDEN, "Only @claude's owner or the admin");
    fails(() -> listed(strangerSession, "claude", null), ErrorCode.FORBIDDEN, "owner or the admin");
    assertThat(ask(adminSession, "claude", false).requestedBy()).isEqualTo("root");
    assertThat(listed(adminSession, "claude", null)).hasSize(1);
    fx.status(admin, "FROZEN");                                    // a frozen admin never acts as one
    fails(() -> ask(adminSession, "chatgpt", false), ErrorCode.FORBIDDEN, "owner or the admin");
    fails(() -> ask(ownerSession, "stranger", false), ErrorCode.FORBIDDEN, "@stranger is not an AI player");
    fails(() -> ask(ownerSession, "nobody", false), ErrorCode.PLAYER_NOT_FOUND, "nobody");
    fx.status(other, "FROZEN");
    fails(() -> ask(ownerSession, "chatgpt", false), ErrorCode.PLAYER_FROZEN, "@chatgpt is FROZEN");
    assertThat(listed(ownerSession, "chatgpt", null)).isEmpty();
    fx.status(other, "DISABLED");
    fails(() -> listed(ownerSession, "chatgpt", null), ErrorCode.PLAYER_NOT_FOUND, "chatgpt");
  }

  @Test
  void unclaimedRequestsExpireAndUnreportedClaimsFail() {
    RunRequestDto first = ask(ownerSession, "claude", false);
    time.advance(Duration.ofMinutes(29));
    assertThat(listed(ownerSession, "claude", null)).extracting(RunRequestDto::status)
        .containsExactly(RunRequestStatus.PENDING);
    time.advance(Duration.ofMinutes(1).plusSeconds(1));
    RunRequestDto expired = listed(ownerSession, "claude", null).get(0);
    assertThat(expired.status()).isEqualTo(RunRequestStatus.EXPIRED);
    assertThat(expired.finishedAt()).isEqualTo(time.now());
    assertThat(expired.message()).isEqualTo("Expired: the trader did not pick it up within 30 minutes. Is the "
        + "claude-trader service running, with @claude enabled in its agents file?");
    assertThat(service.claim(botKey)).isEmpty();                   // an expired request is never claimed
    fails(() -> service.finish(botKey, first.id(), new FinishRunRequest(RunRequestStatus.DONE, null)),
        ErrorCode.RUN_REQUEST_CONFLICT, "The run request is EXPIRED");

    RunRequestDto second = ask(ownerSession, "claude", false);
    service.claim(botKey).orElseThrow();
    time.advance(Duration.ofMinutes(31));
    assertThat(service.claim(botKey)).isEmpty();                   // the claim sweep fails the stale claim
    RunRequestDto failed = listed(ownerSession, "claude", 1).get(0);
    assertThat(failed.id()).isEqualTo(second.id());
    assertThat(failed.status()).isEqualTo(RunRequestStatus.FAILED);
    assertThat(failed.message()).isEqualTo("The trader did not report back within 30 minutes of picking it up.");
    // the trader's own FAILED report after that is a repeat; DONE is a conflict
    assertThat(service.finish(botKey, second.id(), new FinishRunRequest(RunRequestStatus.FAILED, "x")).message())
        .isEqualTo(failed.message());
    fails(() -> service.finish(botKey, second.id(), new FinishRunRequest(RunRequestStatus.DONE, "x")),
        ErrorCode.RUN_REQUEST_CONFLICT, "The run request is FAILED");
    assertThat(ask(ownerSession, "claude", false).status()).isEqualTo(RunRequestStatus.PENDING);
  }

  @Test
  void theListSaysWhenATraderLastAskedForTheBotAndWhetherItCanRun() {
    RunRequestListDto fresh = service.list(ownerSession, "claude", null);
    assertThat(fresh.botStatus()).isEqualTo(PlayerStatus.ACTIVE);
    assertThat(fresh.traderSeenAt()).isNull();                     // no trader has asked for @claude
    assertThat(fresh.requests()).isEmpty();
    assertThat(fresh.asOf()).isEqualTo(NOW);                       // the server's clock, not the browser's
    time.advance(Duration.ofSeconds(30));
    assertThat(service.claim(botKey)).isEmpty();                   // an empty claim is a sign of life too
    assertThat(service.list(ownerSession, "claude", null).traderSeenAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(service.list(ownerSession, "chatgpt", null).traderSeenAt()).isNull();   // per bot

    // frozen while its request waits: the list closes the request, with why
    RunRequestDto first = ask(ownerSession, "claude", false);
    fx.status(bot, "FROZEN");
    RunRequestListDto frozen = service.list(ownerSession, "claude", null);
    assertThat(frozen.botStatus()).isEqualTo(PlayerStatus.FROZEN);
    assertThat(frozen.requests()).singleElement().satisfies(r -> {
      assertThat(r.id()).isEqualTo(first.id());
      assertThat(r.status()).isEqualTo(RunRequestStatus.EXPIRED);
      assertThat(r.finishedAt()).isEqualTo(time.now());
      assertThat(r.message()).isEqualTo("Not run: @claude is FROZEN, so it cannot trade. Request the run again once "
          + "it is ACTIVE.");
    });
    // ... and so does a claim (its key would be refused before that; a status can change while a request waits)
    fx.status(bot, "ACTIVE");
    RunRequestDto second = ask(ownerSession, "claude", false);
    fx.status(bot, "FROZEN");
    assertThat(service.claim(botKey)).isEmpty();
    fx.status(bot, "ACTIVE");
    assertThat(service.claim(botKey)).isEmpty();                   // closed for good
    assertThat(listed(ownerSession, "claude", 1)).singleElement().satisfies(r -> {
      assertThat(r.id()).isEqualTo(second.id());
      assertThat(r.status()).isEqualTo(RunRequestStatus.EXPIRED);
    });
    assertThat(ask(ownerSession, "claude", false).status()).isEqualTo(RunRequestStatus.PENDING);
  }

  @Test
  void claimsAndReportsNeedTheBotsOwnKey() {
    RunRequestDto asked = ask(ownerSession, "claude", false);
    PlayerPrincipal humanKey = TestPrincipals.apiKey(owner.id(), owner.publicId(), owner.handle(), PlayerKind.HUMAN,
        Scopes.ALL, 1L);
    for (PlayerPrincipal caller : List.of(ownerSession, humanKey)) {
      fails(() -> service.claim(caller), ErrorCode.FORBIDDEN, "Only the bot's own API key");
      fails(() -> service.finish(caller, asked.id(), new FinishRunRequest(RunRequestStatus.DONE, null)),
          ErrorCode.FORBIDDEN, "Only the bot's own API key");
    }
    service.claim(botKey).orElseThrow();
    fails(() -> service.finish(otherKey, asked.id(), new FinishRunRequest(RunRequestStatus.DONE, null)),
        ErrorCode.NOT_FOUND, "No run request '" + asked.id() + "' for @chatgpt");
    fails(() -> service.finish(botKey, "arq_nope", new FinishRunRequest(RunRequestStatus.DONE, null)),
        ErrorCode.NOT_FOUND, "arq_nope");
    for (FinishRunRequest bad : new FinishRunRequest[] {null, new FinishRunRequest(null, "x"),
        new FinishRunRequest(RunRequestStatus.PENDING, "x"), new FinishRunRequest(RunRequestStatus.EXPIRED, "x")}) {
      fails(() -> service.finish(botKey, asked.id(), bad), ErrorCode.VALIDATION_FAILED, "status");
    }
    String noisy = "Failed:\nclaude said stk_AbCdEf12_secretsecretsecret was bad\t" + "x".repeat(600);
    RunRequestDto failed = service.finish(botKey, asked.id(), new FinishRunRequest(RunRequestStatus.FAILED, noisy));
    assertThat(failed.message()).startsWith("Failed: claude said [redacted] was bad").hasSize(500)
        .doesNotContain("secretsecret");
  }

  @Test
  void atMostTwentyRequestsPerBotInTwentyFourHours() {
    for (int i = 0; i < 20; i++) {
      RunRequestDto r = ask(ownerSession, "claude", false);
      service.claim(botKey).orElseThrow();
      service.finish(botKey, r.id(), new FinishRunRequest(RunRequestStatus.DONE, "ok"));
      time.advance(Duration.ofMinutes(10));
    }
    assertThatThrownBy(() -> ask(ownerSession, "claude", false)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
      assertThat(e.detail()).startsWith("At most 20 run requests per bot in 24 hours.");
      // the first request (at NOW) leaves the window 24 h later; it is now NOW + 200 min
      assertThat(e.retryAfterS()).isEqualTo(Duration.ofHours(24).minusMinutes(200).toSeconds());
    });
    assertThat(db.jdbc().sql("SELECT COUNT(*) FROM audit_log WHERE action = 'BOT_RUN_REQUEST' "
        + "AND outcome = 'RATE_LIMITED'").query(Integer.class).single()).isEqualTo(1);
    assertThat(ask(ownerSession, "chatgpt", false).status()).isEqualTo(RunRequestStatus.PENDING);  // per bot
    time.set(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
    assertThat(ask(ownerSession, "claude", false).status()).isEqualTo(RunRequestStatus.PENDING);
  }
}
