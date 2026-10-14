package com.stocks.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.web.Page;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.EngineStatus;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestTimes;
import com.stocks.testkit.WebSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** §21.1 settings, invites, audit, system, engine, calendar, corporate actions and admin order cancel. */
class AdminConsoleIT extends ContractMocksWebTest {

  @Autowired SettingsService settings;

  Fixtures.PlayerRef admin;
  WebSession root;

  @BeforeEach
  void setUp() {
    admin = fx.admin(handle("console"));
    root = adminSession(admin);
  }

  MvcResult send(MockHttpServletRequestBuilder b, String json) {
    return root.perform(b.contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void settingsRoundTripWithValidation() {
    MvcResult get = root.perform(get("/api/v1/admin/settings"));
    assertThat(status(get)).as(body(get)).isEqualTo(200);
    assertThat((String) json(get, "$['league.defaultStartingCash']")).isEqualTo("500.00");
    assertThat((String) json(get, "$['social.tradeFeedDelay']")).isEqualTo("PT0S");   // public mode (§16.7)
    assertThat(body(get)).doesNotContain("social.holdingsVisibility");                // retired

    MvcResult put = send(put("/api/v1/admin/settings"),
        "{\"league.defaultStartingCash\":\"750\",\"league.allowSelfReset\":true,\"social.tradeFeedDelay\":\"pt5m\"}");
    assertThat(status(put)).as(body(put)).isEqualTo(200);
    assertThat((String) json(put, "$['league.defaultStartingCash']")).isEqualTo("750.00");
    assertThat((Boolean) json(put, "$['league.allowSelfReset']")).isTrue();
    assertThat((String) json(put, "$['social.tradeFeedDelay']")).isEqualTo("PT5M");
    assertThat(settings.defaultStartingCash()).isEqualByComparingTo("750.00");

    MvcResult bad = send(patch("/api/v1/admin/settings"),
        "{\"league.allowSelfReset\":false,\"league.defaultStartingCash\":\"0.99\"}");
    assertThat(status(bad)).isEqualTo(422);
    assertThat(code(bad)).isEqualTo("SETTING_INVALID");
    assertThat(settings.allowSelfReset()).as("all or nothing").isTrue();
    assertThat(code(send(patch("/api/v1/admin/settings"), "{\"social.tradeFeedDelay\":\"soon\"}")))
        .isEqualTo("SETTING_INVALID");
    MvcResult retired = send(patch("/api/v1/admin/settings"), "{\"social.holdingsVisibility\":\"PUBLIC\"}");
    assertThat(status(retired)).as("an unknown member is ignored").isEqualTo(200);
    assertThat((String) json(retired, "$['social.tradeFeedDelay']")).isEqualTo("PT5M");   // nothing changed
    assertThat(body(retired)).doesNotContain("social.holdingsVisibility");

    MvcResult restore = send(patch("/api/v1/admin/settings"),
        "{\"league.defaultStartingCash\":\"500.00\",\"league.allowSelfReset\":false,\"social.tradeFeedDelay\":\"PT0S\"}");
    assertThat(status(restore)).isEqualTo(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'SETTINGS_UPDATE' AND actor_player_id = ?")
        .param(admin.id()).query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void invitesCreateListRevoke() {
    MvcResult created = send(post("/api/v1/admin/invites"), "{\"maxUses\":3,\"expiresInHours\":48}");
    assertThat(status(created)).as(body(created)).isEqualTo(201);
    String id = json(created, "$.inviteId");
    String code = json(created, "$.code");
    assertThat(id).startsWith("inv_");
    assertThat((String) json(created, "$.url")).isEqualTo("http://localhost:5173/join/" + code);
    assertThat(java.time.Instant.parse(json(created, "$.expiresAt")))
        .isEqualTo(time.now().plus(java.time.Duration.ofHours(48)));
    assertThat(jdbc.sql("SELECT code_hash FROM invite WHERE public_id = ?").param(id).query(String.class).single())
        .hasSize(64).isNotEqualTo(code);

    MvcResult list = root.perform(get("/api/v1/admin/invites"));
    List<String> ids = json(list, "$[*].inviteId");
    assertThat(ids).contains(id);
    assertThat(status(root.perform(delete("/api/v1/admin/invites/" + id)))).isEqualTo(204);
    MvcResult after = root.perform(get("/api/v1/admin/invites"));
    assertThat(body(after)).contains("\"revokedAt\":\"");
    assertThat(status(root.perform(delete("/api/v1/admin/invites/inv_nothing")))).isEqualTo(404);
    assertThat(code(send(post("/api/v1/admin/invites"), "{\"maxUses\":51,\"expiresInHours\":1}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(send(post("/api/v1/admin/invites"), "{\"maxUses\":1,\"expiresInHours\":721}")))
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void auditFiltersAndPages() {
    Fixtures.PlayerRef p = fx.human(handle("audited"));
    for (int i = 0; i < 3; i++) {
      WebSession.anonymous(mvc).login(p.handle(), "wrong-password-" + i);
    }
    MvcResult page1 = root.perform(get("/api/v1/admin/audit?actor=" + p.handle() + "&limit=2"));
    assertThat(status(page1)).as(body(page1)).isEqualTo(200);
    List<String> actions = json(page1, "$.items[*].action");
    assertThat(actions).containsExactly("LOGIN_FAIL", "LOGIN_FAIL");
    assertThat((String) json(page1, "$.items[0].actor")).isEqualTo(p.handle());
    assertThat((String) json(page1, "$.items[0].detail.code")).isEqualTo("BAD_CREDENTIALS");
    String cursor = json(page1, "$.nextCursor");
    MvcResult page2 = root.perform(get("/api/v1/admin/audit?actor=" + p.handle() + "&limit=2&cursor=" + cursor));
    List<String> rest = json(page2, "$.items[*].action");
    assertThat(rest).containsExactly("LOGIN_FAIL");
    assertThat((Object) json(page2, "$.nextCursor")).isNull();

    MvcResult auth = root.perform(get("/api/v1/admin/audit?category=auth&action=login_fail&actor=" + p.handle()));
    List<String> all = json(auth, "$.items[*].action");
    assertThat(all).hasSize(3);
    assertThat(code(root.perform(get("/api/v1/admin/audit?category=NOPE")))).isEqualTo("VALIDATION_FAILED");
    MvcResult nobody = root.perform(get("/api/v1/admin/audit?actor=nobody_at_all"));
    List<Object> none = json(nobody, "$.items");
    assertThat(none).isEmpty();
  }

  @Test
  void systemStatusEngineJobsAndHaltResume() {
    EngineStatus running = new EngineStatus(EngineState.RUNNING, 3, 42, TestTimes.START, null, null,
        new InvariantResult(TestTimes.START, true, List.of()));
    when(engineControl.status()).thenReturn(running);
    MvcResult sys = root.perform(get("/api/v1/admin/system"));
    assertThat(status(sys)).as(body(sys)).isEqualTo(200);
    assertThat((String) json(sys, "$.engine.status")).isEqualTo("RUNNING");
    assertThat((Integer) json(sys, "$.engine.queueDepth")).isEqualTo(3);
    assertThat((Boolean) json(sys, "$.invariants.ok")).isTrue();
    assertThat((String) json(sys, "$.db")).isIn("h2", "postgresql");
    assertThat((String) json(sys, "$.version")).isNotBlank();

    EngineStatus halted = new EngineStatus(EngineState.HALTED, 0, 42, TestTimes.START, "maintenance", null, null);
    when(engineControl.halt("maintenance", admin.id())).thenReturn(halted);
    MvcResult h = send(post("/api/v1/admin/engine/halt"), "{\"reason\":\"maintenance\"}");
    assertThat(status(h)).as(body(h)).isEqualTo(200);
    assertThat((String) json(h, "$.engine.status")).isEqualTo("HALTED");
    assertThat((String) json(h, "$.engine.haltReason")).isEqualTo("maintenance");
    when(engineControl.resume(admin.id())).thenReturn(running);
    MvcResult r = send(post("/api/v1/admin/engine/resume"), "");
    assertThat((String) json(r, "$.engine.status")).isEqualTo("RUNNING");

    assertThat(status(send(post("/api/v1/admin/system/jobs/NO_SUCH_JOB/run"), "{}"))).isEqualTo(404);
    // review regression: a run key that has not happened yet is refused before the 202 (it would apply a split or pay
    // a dividend ahead of its date)
    MvcResult future = send(post("/api/v1/admin/system/jobs/PAY_DIVIDENDS/run"), "{\"runKey\":\"2026-10-14\"}");
    assertThat(status(future)).as(body(future)).isEqualTo(400);
    assertThat((String) json(future, "$.code")).isEqualTo("VALIDATION_FAILED");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action IN ('ENGINE_HALT','ENGINE_RESUME') "
        + "AND actor_player_id = ?").param(admin.id()).query(Long.class).single()).isEqualTo(2);
  }

  @Test
  void systemStatusSurvivesAStubbedEngine() {
    when(engineControl.status()).thenThrow(com.stocks.common.error.ApiException.notImplemented("EngineControl"));
    MvcResult sys = root.perform(get("/api/v1/admin/system"));
    assertThat(status(sys)).isEqualTo(200);
    assertThat((String) json(sys, "$.engine.status")).isEqualTo("STARTING");
    assertThat((Boolean) json(sys, "$.invariants.ok")).isFalse();
  }

  @Test
  void calendarCorporateActionsAndOrdersDelegate() {
    LocalDate day = LocalDate.of(2026, 11, 27);
    when(calendarAdmin.putOverride(eq(day), eq(true), isNull(), isNull(), eq("storm"), eq(admin.id())))
        .thenReturn(new CalendarOverride(day, true, null, null, "storm", admin.handle(), TestTimes.START));
    MvcResult c = send(post("/api/v1/admin/calendar/overrides"),
        "{\"date\":\"2026-11-27\",\"closed\":true,\"reason\":\"storm\"}");
    assertThat(status(c)).as(body(c)).isEqualTo(201);
    assertThat((String) json(c, "$.createdBy")).isEqualTo(admin.handle());
    when(calendarAdmin.overrides(any(), any())).thenReturn(List.of());
    assertThat(status(root.perform(get("/api/v1/admin/calendar/overrides?from=2026-11-01&to=2026-12-31"))))
        .isEqualTo(200);
    assertThat(code(root.perform(get("/api/v1/admin/calendar/overrides?from=2026-01-01&to=2027-12-31"))))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(status(root.perform(delete("/api/v1/admin/calendar/overrides/2026-11-27")))).isEqualTo(204);
    verify(calendarAdmin).deleteOverride(day, admin.id());

    when(corporateActionQueries.list(eq(CorporateActionStatus.NEEDS_ADMIN), any(), anyInt()))
        .thenReturn(Page.empty());
    assertThat(status(root.perform(get("/api/v1/admin/corporate-actions?status=needs_admin")))).isEqualTo(200);
    send(post("/api/v1/admin/corporate-actions/cac_1/resolve"),
        "{\"resolution\":\"APPLY\",\"oldRate\":\"1\",\"newRate\":\"2\",\"note\":\"confirmed 2:1\"}");
    verify(corporateActions).resolve("cac_1", CorporateActionResolution.APPLY, new BigDecimal("1"),
        new BigDecimal("2"), "confirmed 2:1", admin.id());

    root.perform(delete("/api/v1/admin/orders/ord_123"));
    verify(adminOrders).cancel(eq("ord_123"), eq(CancelReason.ADMIN), eq(admin.id()), any());
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'ORDER_CANCEL' AND actor_player_id = ?")
        .param(admin.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void aNonAdminSessionAndKeysAreRefusedEverywhere() throws Exception {
    Fixtures.PlayerRef pleb = fx.human(handle("pleb"));
    WebSession s = WebSession.login(mvc, pleb.handle(), Fixtures.PASSWORD);
    assertThat(code(s.perform(get("/api/v1/admin/settings")))).isEqualTo("FORBIDDEN");
    String key = fx.apiKey(admin, pepper());
    assertThat(code(mvc.perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + key))
        .andReturn())).isEqualTo("ADMIN_REQUIRES_SESSION");
    assertThat(status(mvc.perform(get("/api/v1/admin/settings")).andReturn())).isIn(401, 403);
    verify(engineControl, org.mockito.Mockito.never()).halt(any(), anyLong());
  }
}
