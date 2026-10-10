package com.stocks.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.RecordedEvents;
import com.stocks.testkit.TestTimes;
import com.stocks.testkit.WebSession;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** §21.1 admin player workflows over the real chains, with WS3/WS4 as mocks (§10.5 two-step order verified). */
class AdminPlayersIT extends ContractMocksWebTest {

  Fixtures.PlayerRef admin;
  WebSession root;

  @BeforeEach
  void setUp() {
    admin = fx.admin(handle("boss"));
    root = adminSession(admin);
  }

  static MvcResult send(WebSession s, MockHttpServletRequestBuilder b, String json) {
    return s.perform(b.contentType(MediaType.APPLICATION_JSON).content(json));
  }

  static AccountDto account(String id, int runNo) {
    BigDecimal cash = new BigDecimal("500.00");
    BigDecimal zero = new BigDecimal("0.00");
    return new AccountDto(id, runNo, AccountType.CASH, AccountStatus.ACTIVE, AccountRestriction.NONE, null, null,
        cash, zero, zero, new AccountDto.AccountCashDto(cash, cash, zero, cash, cash), zero, cash, cash, cash, zero,
        new BigDecimal("0.0000000000"), new BigDecimal("1.000000000000000000"), zero, new BigDecimal("0.0000000000"),
        new AccountDto.AccountGfvDto(0, null, 3, 4), 0, TestTimes.START, DataQuality.SIMULATED, TestTimes.START);
  }

  @Test
  void createAHumanAndAnAiBotWithAKeyThatWorks() throws Exception {
    when(accountQueries.activeAccountId(anyLong())).thenThrow(ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "x"));
    String human = handle("newbie");
    MvcResult h = send(root, post("/api/v1/admin/players"), "{\"kind\":\"HUMAN\",\"handle\":\"" + human
        + "\",\"displayName\":\"New Bie\",\"password\":\"long-enough-password\",\"startingCash\":\"1000\"}");
    assertThat(status(h)).as(body(h)).isEqualTo(201);
    assertThat((String) json(h, "$.player.handle")).isEqualTo(human);
    assertThat((Object) json(h, "$.apiKey")).isNull();
    long humanId = jdbc.sql("SELECT id FROM player WHERE handle = ?").param(human).query(Long.class).single();
    verify(accountService).openInitialAccount(humanId, new BigDecimal("1000.00"), admin.id());
    verify(watchlists).seedDefault(humanId);

    String bot = handle("gpt");
    MvcResult b = send(root, post("/api/v1/admin/players"), "{\"kind\":\"AI\",\"handle\":\"" + bot
        + "\",\"displayName\":\"GPT Bot\",\"aiModel\":\"ChatGPT\"}");
    assertThat(status(b)).as(body(b)).isEqualTo(201);
    assertThat((String) json(b, "$.player.kind")).isEqualTo("AI");
    assertThat((String) json(b, "$.player.aiModel")).isEqualTo("ChatGPT");
    assertThat((String) json(b, "$.player.ownerHandle")).isEqualTo(admin.handle());
    assertThat((String) json(b, "$.player.role")).isEqualTo("PLAYER");
    assertThat((Integer) json(b, "$.player.activeKeys")).isEqualTo(1);
    List<String> scopes = json(b, "$.apiKey.key.scopes");
    assertThat(scopes).containsExactly("account:read", "league:read", "market:read", "trade");
    String token = json(b, "$.apiKey.token");
    MvcResult me = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andReturn();
    assertThat((String) json(me, "$.handle")).isEqualTo(bot);
    long botId = jdbc.sql("SELECT id FROM player WHERE handle = ?").param(bot).query(Long.class).single();
    verify(accountService).openInitialAccount(botId, new BigDecimal("500.00"), admin.id());

    MvcResult adminRoute = mvc.perform(get("/api/v1/admin/players").header("Authorization", "Bearer " + token))
        .andReturn();
    assertThat(code(adminRoute)).isEqualTo("ADMIN_REQUIRES_SESSION");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'PLAYER_CREATE' AND actor_player_id = ?")
        .param(admin.id()).query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void createValidation() {
    assertThat(code(send(root, post("/api/v1/admin/players"),
        "{\"kind\":\"HUMAN\",\"handle\":\"" + handle("np") + "\",\"displayName\":\"No Pw\"}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(root, post("/api/v1/admin/players"), "{\"kind\":\"AI\",\"handle\":\"" + handle("ai")
        + "\",\"displayName\":\"Ai\",\"aiModel\":\"X\",\"password\":\"long-enough-password\"}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(root, post("/api/v1/admin/players"),
        "{\"kind\":\"AI\",\"handle\":\"" + handle("ai") + "\",\"displayName\":\"Ai\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(root, post("/api/v1/admin/players"), "{\"kind\":\"HUMAN\",\"handle\":\"" + handle("w")
        + "\",\"displayName\":\"Weak\",\"password\":\"short\"}"))).isEqualTo("WEAK_PASSWORD");
    assertThat(code(send(root, post("/api/v1/admin/players"), "{\"kind\":\"HUMAN\",\"handle\":\"" + admin.handle()
        + "\",\"displayName\":\"Dup\",\"password\":\"long-enough-password\"}"))).isEqualTo("HANDLE_TAKEN");
    assertThat(code(send(root, post("/api/v1/admin/players"), "{\"kind\":\"HUMAN\",\"handle\":\"" + handle("c")
        + "\",\"displayName\":\"Cash\",\"password\":\"long-enough-password\",\"startingCash\":\"0.50\"}")))
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void listAndDetailNeverShowLivePositions() {
    Fixtures.PlayerRef p = fx.human(handle("listed"));
    Fixtures.AccountRef acc = fx.account(p, "500.00");
    fx.position(acc, "AAPL", "1", "200.0000");
    MvcResult list = root.perform(get("/api/v1/admin/players"));
    assertThat(status(list)).as(body(list)).isEqualTo(200);
    List<String> handles = json(list, "$[*].handle");
    assertThat(handles).contains(p.handle(), admin.handle());
    MvcResult filtered = root.perform(get("/api/v1/admin/players?kind=ai&status=ACTIVE"));
    assertThat(status(filtered)).isEqualTo(200);
    List<String> kinds = json(filtered, "$[*].kind");
    assertThat(kinds).doesNotContain("HUMAN");

    MvcResult detail = root.perform(get("/api/v1/admin/players/" + p.handle()));
    assertThat(status(detail)).as(body(detail)).isEqualTo(200);
    assertThat((String) json(detail, "$.player.accountId")).isEqualTo(acc.publicId());
    assertThat((Integer) json(detail, "$.player.runNo")).isEqualTo(1);
    assertThat((String) json(detail, "$.player.cash")).isEqualTo("300.00");
    assertThat((String) json(detail, "$.player.netContributions")).isEqualTo("500.00");
    assertThat((String) json(detail, "$.player.restriction")).isEqualTo("NONE");
    assertThat(body(detail)).doesNotContain("\"positions\"").doesNotContain("\"openOrders\":[");
    verify(accountQueries, never()).positions(anyLong());

    assertThat(code(root.perform(get("/api/v1/admin/players/nobody_here")))).isEqualTo("PLAYER_NOT_FOUND");
  }

  @Test
  void freezeIsTwoStepsAndABotSeesPlayerFrozen() throws Exception {
    Fixtures.PlayerRef owner = fx.human(handle("owner"));
    Fixtures.PlayerRef bot = fx.ai(handle("robo"), owner);
    String key = fx.apiKey(bot, pepper());
    WebSession ownerSession = WebSession.login(mvc, owner.handle(), Fixtures.PASSWORD);

    MvcResult r = send(root, post("/api/v1/admin/players/" + bot.handle() + "/freeze"),
        "{\"reason\":\"runaway loop\"}");
    assertThat(status(r)).as(body(r)).isEqualTo(200);
    assertThat((String) json(r, "$.status")).isEqualTo("FROZEN");
    verify(accountService).restrict(bot.id(), AccountRestriction.FROZEN, CancelReason.PLAYER_FROZEN, "runaway loop",
        admin.id());
    MvcResult frozen = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andReturn();
    assertThat(status(frozen)).isEqualTo(403);
    assertThat(code(frozen)).isEqualTo("PLAYER_FROZEN");
    assertThat(RecordedEvents.of(PlayerStatusChanged.class))
        .anyMatch(e -> e.playerId() == bot.id() && e.status() == PlayerStatus.FROZEN);

    send(root, post("/api/v1/admin/players/" + bot.handle() + "/unfreeze"), "{}");
    verify(accountService).liftAdminRestriction(bot.id(), admin.id());
    assertThat(status(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andReturn()))
        .isEqualTo(200);

    // freezing a human ends their sessions; revokeKeys revokes their keys
    String ownerKey = fx.apiKey(owner, pepper());
    send(root, post("/api/v1/admin/players/" + owner.handle() + "/freeze"),
        "{\"reason\":\"cheating\",\"revokeKeys\":true}");
    assertThat(status(ownerSession.perform(get("/api/v1/me")))).isEqualTo(401);
    assertThat(code(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + ownerKey)).andReturn()))
        .isEqualTo("API_KEY_REVOKED");
    assertThat(status(WebSession.anonymous(mvc).login(owner.handle(), Fixtures.PASSWORD))).as("frozen may look")
        .isEqualTo(200);
  }

  @Test
  void aFrozenAdminMayLookButNeverActAsAdmin() {
    Fixtures.PlayerRef deputy = fx.admin(handle("deputy"));
    MvcResult frozen = send(root, post("/api/v1/admin/players/" + deputy.handle() + "/freeze"),
        "{\"reason\":\"compromised\"}");
    assertThat(status(frozen)).as(body(frozen)).isEqualTo(200);
    WebSession d = WebSession.login(mvc, deputy.handle(), Fixtures.PASSWORD);
    assertThat(status(d.perform(get("/api/v1/me")))).as("a frozen human may log in and look").isEqualTo(200);
    MvcResult list = d.perform(get("/api/v1/admin/players"));
    assertThat(status(list)).isEqualTo(403);
    assertThat(code(list)).isEqualTo("FORBIDDEN");
    assertThat(code(send(d, post("/api/v1/admin/players/" + deputy.handle() + "/unfreeze"), "{}")))
        .isEqualTo("FORBIDDEN");

    send(root, post("/api/v1/admin/players/" + deputy.handle() + "/unfreeze"), "{}");
    assertThat(status(d.perform(get("/api/v1/admin/players")))).as("unfrozen: the same session works again")
        .isEqualTo(200);
  }

  @Test
  void theEngineStepRunsAfterThePlayerStepCommitted() {
    Fixtures.PlayerRef p = fx.human(handle("order"));
    String[] statusSeenByEngine = {null};
    doAnswer(inv -> {
      statusSeenByEngine[0] = jdbc.sql("SELECT status FROM player WHERE id = ?").param(p.id())
          .query(String.class).single();
      return null;
    }).when(accountService).restrict(anyLong(), any(), any(), any(), anyLong());
    send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"why\"}");
    assertThat(statusSeenByEngine[0]).isEqualTo("FROZEN");
  }

  @Test
  void anEngineFailureAfterThePlayerStepIsReportedAndTheFreezeCanBeRetried() {
    Fixtures.PlayerRef p = fx.human(handle("busy"));
    doThrow(ApiException.of(ErrorCode.ENGINE_BUSY, "full")).doNothing().when(accountService)
        .restrict(anyLong(), any(), any(), any(), anyLong());
    MvcResult busy = send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"why\"}");
    assertThat(code(busy)).isEqualTo("ENGINE_BUSY");
    assertThat(jdbc.sql("SELECT status FROM player WHERE id = ?").param(p.id()).query(String.class).single())
        .isEqualTo("FROZEN");
    MvcResult retry = send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"why\"}");
    assertThat(status(retry)).isEqualTo(200);
  }

  @Test
  void adminsCannotFreezeOrDemoteThemselvesAndBotsCannotBecomeAdmin() {
    MvcResult self = send(root, post("/api/v1/admin/players/" + admin.handle() + "/freeze"), "{\"reason\":\"x\"}");
    assertThat(code(self)).isEqualTo("FORBIDDEN");
    MvcResult demote = send(root, patch("/api/v1/admin/players/" + admin.handle()), "{\"role\":\"PLAYER\"}");
    assertThat(code(demote)).isEqualTo("FORBIDDEN");
    // a frozen admin may still log in (§17.3), so the kill switch must not be undoable by its target
    MvcResult unfreezeSelf = send(root, post("/api/v1/admin/players/" + admin.handle() + "/unfreeze"), "{}");
    assertThat(code(unfreezeSelf)).isEqualTo("FORBIDDEN");
    Fixtures.PlayerRef bot = fx.ai(handle("wannabe"), admin);
    MvcResult promote = send(root, patch("/api/v1/admin/players/" + bot.handle()), "{\"role\":\"ADMIN\"}");
    assertThat(status(promote)).isEqualTo(403);
    assertThat(code(promote)).isEqualTo("FORBIDDEN");
    assertThat(jdbc.sql("SELECT role FROM player WHERE id = ?").param(bot.id()).query(String.class).single())
        .isEqualTo("PLAYER");
  }

  @Test
  void patchDisablesPromotesAndDropsTrade() throws Exception {
    Fixtures.PlayerRef p = fx.human(handle("patchy"));
    jdbc.sql("UPDATE player SET api_trading_allowed = TRUE WHERE id = ?").param(p.id()).update();
    String tradeKey = fx.apiKey(p, java.util.Set.of("trade", "market:read"), pepper());
    String tradeOnly = fx.apiKey(p, java.util.Set.of("trade"), pepper());
    WebSession ps = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);

    MvcResult noTrade = send(root, patch("/api/v1/admin/players/" + p.handle()), "{\"apiTradingAllowed\":false}");
    assertThat(status(noTrade)).as(body(noTrade)).isEqualTo(200);
    assertThat(jdbc.sql("SELECT scopes FROM api_key WHERE key_id = ?").param(tradeKey.substring(4, 16))
        .query(String.class).single()).isEqualTo("market:read");
    assertThat(code(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tradeOnly)).andReturn()))
        .isEqualTo("API_KEY_REVOKED");

    MvcResult promote = send(root, patch("/api/v1/admin/players/" + p.handle()),
        "{\"role\":\"ADMIN\",\"displayName\":\"Deputy\"}");
    assertThat((String) json(promote, "$.role")).isEqualTo("ADMIN");
    assertThat((String) json(promote, "$.displayName")).isEqualTo("Deputy");
    assertThat(status(ps.perform(get("/api/v1/me")))).as("role change ends sessions").isEqualTo(401);
    WebSession deputy = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    assertThat(status(deputy.perform(get("/api/v1/admin/players")))).isEqualTo(200);

    MvcResult disable = send(root, patch("/api/v1/admin/players/" + p.handle()), "{\"status\":\"DISABLED\"}");
    assertThat((String) json(disable, "$.status")).isEqualTo("DISABLED");
    verify(accountService).restrict(p.id(), AccountRestriction.FROZEN, CancelReason.PLAYER_DISABLED,
        "Disabled by the admin", admin.id());
    assertThat(code(WebSession.anonymous(mvc).login(p.handle(), Fixtures.PASSWORD))).isEqualTo("PLAYER_DISABLED");
    assertThat(code(send(root, patch("/api/v1/admin/players/" + p.handle()), "{\"status\":\"FROZEN\"}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(root, post("/api/v1/admin/players/" + p.handle() + "/unfreeze"), "{}")))
        .as("re-enable with PATCH status ACTIVE, not unfreeze").isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"x\"}")))
        .isEqualTo("VALIDATION_FAILED");
    send(root, patch("/api/v1/admin/players/" + p.handle()), "{\"status\":\"ACTIVE\"}");
    verify(accountService).liftAdminRestriction(p.id(), admin.id());
    assertThat(status(WebSession.anonymous(mvc).login(p.handle(), Fixtures.PASSWORD))).isEqualTo(200);
  }

  @Test
  void adminPasswordResetEndsSessionsAndRevokesKeys() throws Exception {
    Fixtures.PlayerRef p = fx.human(handle("forgot"));
    String key = fx.apiKey(p, pepper());
    WebSession ps = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    MvcResult r = send(root, post("/api/v1/admin/players/" + p.handle() + "/password"),
        "{\"newPassword\":\"temporary-password-1\"}");
    assertThat(status(r)).as(body(r)).isEqualTo(204);
    assertThat(status(ps.perform(get("/api/v1/me")))).isEqualTo(401);
    assertThat(code(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key)).andReturn()))
        .isEqualTo("API_KEY_REVOKED");
    assertThat(status(WebSession.anonymous(mvc).login(p.handle(), "temporary-password-1"))).isEqualTo(200);
    Fixtures.PlayerRef bot = fx.ai(handle("nopw"), admin);
    assertThat(code(send(root, post("/api/v1/admin/players/" + bot.handle() + "/password"),
        "{\"newPassword\":\"temporary-password-1\"}"))).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void resetGoesThroughTheEngineWithAnIdempotencyKeyAndReplays() {
    Fixtures.PlayerRef p = fx.human(handle("resetme"));
    when(accountService.reset(eq(p.id()), any(), eq("Fresh start"), any(IdempotencyRef.class), eq(admin.id())))
        .thenReturn(account("acc_RUN2", 2));
    MvcResult missing = send(root, post("/api/v1/admin/players/" + p.handle() + "/reset"), "{\"memo\":\"x\"}");
    assertThat(status(missing)).isEqualTo(400);
    assertThat(code(missing)).isEqualTo("VALIDATION_FAILED");

    MvcResult r = root.perform(post("/api/v1/admin/players/" + p.handle() + "/reset")
        .header(IdempotencyKeys.HEADER, "reset-1").contentType(MediaType.APPLICATION_JSON)
        .content("{\"memo\":\"Fresh start\"}"));
    assertThat(status(r)).as(body(r)).isEqualTo(200);
    assertThat((Integer) json(r, "$.runNo")).isEqualTo(2);
    ArgumentCaptor<IdempotencyRef> ref = ArgumentCaptor.forClass(IdempotencyRef.class);
    verify(accountService).reset(eq(p.id()), isNull(), eq("Fresh start"), ref.capture(), eq(admin.id()));
    assertThat(ref.getValue().scope()).isEqualTo("admin.reset");
    assertThat(ref.getValue().key()).isEqualTo("reset-1");
    assertThat(ref.getValue().requestHash()).hasSize(64);

    // the engine stored the response: a retry is answered from it, without a second command
    jdbc.sql("""
            INSERT INTO idempotency_record (scope, idem_key, request_hash, status_code, response_body, created_at,
                                            expires_at) VALUES (?, ?, ?, 200, ?, ?, ?)""")
        .params("admin.reset", "reset-1", ref.getValue().requestHash(), body(r), Jdbc.ts(time.now()),
            Jdbc.ts(time.now().plusSeconds(7 * 86400)))
        .update();
    MvcResult replay = root.perform(post("/api/v1/admin/players/" + p.handle() + "/reset")
        .header(IdempotencyKeys.HEADER, "reset-1").contentType(MediaType.APPLICATION_JSON)
        .content("{\"memo\":\"Fresh start\"}"));
    assertThat(status(replay)).isEqualTo(200);
    assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
    assertThat((String) json(replay, "$.accountId")).isEqualTo("acc_RUN2");
    MvcResult conflict = root.perform(post("/api/v1/admin/players/" + p.handle() + "/reset")
        .header(IdempotencyKeys.HEADER, "reset-1").contentType(MediaType.APPLICATION_JSON)
        .content("{\"memo\":\"Another memo\"}"));
    assertThat(status(conflict)).isEqualTo(409);
    assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    verify(accountService, org.mockito.Mockito.times(1)).reset(anyLong(), any(), any(), any(), anyLong());
  }

  @Test
  void aStoredReferenceInsteadOfAFullAccountIsReplayedByTheEngineNotHalfEmpty() {
    Fixtures.PlayerRef p = fx.human(handle("refreset"));
    when(accountService.reset(eq(p.id()), any(), eq("Again"), any(IdempotencyRef.class), eq(admin.id())))
        .thenReturn(account("acc_LIVE", 2));
    MvcResult first = root.perform(post("/api/v1/admin/players/" + p.handle() + "/reset")
        .header(IdempotencyKeys.HEADER, "reset-ref").contentType(MediaType.APPLICATION_JSON)
        .content("{\"memo\":\"Again\"}"));
    assertThat(status(first)).as(body(first)).isEqualTo(200);
    ArgumentCaptor<IdempotencyRef> ref = ArgumentCaptor.forClass(IdempotencyRef.class);
    verify(accountService).reset(eq(p.id()), isNull(), eq("Again"), ref.capture(), eq(admin.id()));
    // what the engine may store for a reset: a reference it rebuilds the live account from (§17.6 step 2)
    jdbc.sql("""
            INSERT INTO idempotency_record (scope, idem_key, request_hash, status_code, response_body, created_at,
                                            expires_at) VALUES (?, ?, ?, 200, ?, ?, ?)""")
        .params("admin.reset", "reset-ref", ref.getValue().requestHash(),
            "{\"accountId\":\"acc_LIVE\",\"runNo\":2}", Jdbc.ts(time.now()),
            Jdbc.ts(time.now().plusSeconds(7 * 86400)))
        .update();
    MvcResult retry = root.perform(post("/api/v1/admin/players/" + p.handle() + "/reset")
        .header(IdempotencyKeys.HEADER, "reset-ref").contentType(MediaType.APPLICATION_JSON)
        .content("{\"memo\":\"Again\"}"));
    assertThat(status(retry)).isEqualTo(200);
    assertThat((String) json(retry, "$.cash.total")).as("a full account, not a half-empty replay")
        .isEqualTo("500.00");
    verify(accountService, org.mockito.Mockito.times(2)).reset(eq(p.id()), any(), any(), any(), anyLong());
  }

  @Test
  void breakGlassWritesTheAdminViewBeforeShowingAnything() {
    Fixtures.PlayerRef p = fx.human(handle("watched"));
    when(accountQueries.activeAccountId(p.id())).thenReturn(4242L);
    when(accountQueries.positions(4242L)).thenReturn(List.of());
    when(orderQueries.list(eq(4242L), any(OrderFilter.class), any(), eq(200))).thenReturn(Page.empty());
    assertThat(code(send(root, post("/api/v1/admin/players/" + p.handle() + "/live-view"), "{\"reason\":\"hm\"}")))
        .isEqualTo("VALIDATION_FAILED");
    MvcResult r = send(root, post("/api/v1/admin/players/" + p.handle() + "/live-view"),
        "{\"reason\":\"suspected bug in fills\"}");
    assertThat(status(r)).as(body(r)).isEqualTo(200);
    assertThat((String) json(r, "$.reason")).isEqualTo("suspected bug in fills");
    assertThat((String) json(r, "$.player.handle")).isEqualTo(p.handle());
    InOrder order = inOrder(accountService, accountQueries);
    order.verify(accountService).recordAdminView(4242L, "suspected bug in fills", admin.id());
    order.verify(accountQueries).positions(4242L);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'PLAYER_LIVE_VIEW' AND target_id = ?")
        .param(p.publicId()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void adminKeysForBots() throws Exception {
    Fixtures.PlayerRef bot = fx.ai(handle("keyed"), admin);
    MvcResult issued = send(root, post("/api/v1/admin/players/" + bot.handle() + "/api-keys"),
        "{\"name\":\"runner\",\"expiresInDays\":90}");
    assertThat(status(issued)).as(body(issued)).isEqualTo(201);
    String token = json(issued, "$.token");
    String keyId = json(issued, "$.key.keyId");
    assertThat(token).startsWith("stk_" + keyId + "_");
    assertThat(jdbc.sql("SELECT secret_hmac FROM api_key WHERE key_id = ?").param(keyId).query(String.class)
        .single()).hasSize(64).doesNotContain(token.substring(17));
    MvcResult list = root.perform(get("/api/v1/admin/players/" + bot.handle() + "/api-keys"));
    List<String> ids = json(list, "$[*].keyId");
    assertThat(ids).contains(keyId);
    assertThat(status(root.perform(delete("/api/v1/admin/api-keys/" + keyId)))).isEqualTo(204);
    assertThat(code(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andReturn()))
        .isEqualTo("API_KEY_REVOKED");
    assertThat(status(root.perform(delete("/api/v1/admin/api-keys/nonexistent12")))).isEqualTo(404);

  }

  /**
   * Review regression (§16.7 item 3, §21.1): the admin used to be able to mint a read key for a human and read their
   * live positions and orders with it, with no break-glass event, audit or notification. Keys of humans are personal
   * now; the admin issues keys for AI players only.
   */
  @Test
  void theAdminCannotIssueKeysForHumans() throws Exception {
    Fixtures.PlayerRef human = fx.human(handle("plain"));
    for (String body : List.of("{\"name\":\"script\"}", "{\"name\":\"script\",\"scopes\":[\"account:read\"]}")) {
      MvcResult humanKey = send(root, post("/api/v1/admin/players/" + human.handle() + "/api-keys"), body);
      assertThat(status(humanKey)).as(body(humanKey)).isEqualTo(403);
      assertThat(code(humanKey)).isEqualTo("FORBIDDEN");
    }
    assertThat(jdbc.sql("SELECT COUNT(*) FROM api_key WHERE player_id = ?").param(human.id()).query(Long.class)
        .single()).isZero();
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'KEY_ISSUE' AND outcome = 'DENIED'"
        + " AND target_id = ?").param(human.publicId()).query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void rWritesNeedARecentPassword() {
    Fixtures.PlayerRef p = fx.human(handle("later"));
    time.advance(java.time.Duration.ofMinutes(20));
    MvcResult stale = send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"x\"}");
    assertThat(code(stale)).isEqualTo("RECENT_AUTH_REQUIRED");
    assertThat(status(root.perform(get("/api/v1/admin/players")))).as("reads stay open").isEqualTo(200);
    send(root, post("/api/v1/auth/reauth"), "{\"password\":\"" + Fixtures.PASSWORD + "\"}");
    MvcResult ok = send(root, post("/api/v1/admin/players/" + p.handle() + "/freeze"), "{\"reason\":\"x\"}");
    assertThat(status(ok)).as(body(ok)).isEqualTo(200);
  }
}
