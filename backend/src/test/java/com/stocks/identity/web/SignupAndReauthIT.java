package com.stocks.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.admin.dto.InviteCreatedDto;
import com.stocks.identity.InviteService;
import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §17.3 invite-only signup and §17.1 {@code POST /auth/reauth} + {@code @RecentAuth}, end to end over MockMvc. */
class SignupAndReauthIT extends ContractMocksWebTest {

  @Autowired InviteService invites;

  InviteCreatedDto invite(int uses) {
    Fixtures.PlayerRef admin = fx.admin(handle("inviter"));
    return invites.create(uses, 24, admin.id());
  }

  MvcResult signup(WebSession s, String code, String handle, String displayName, String password) {
    if (s.xsrf() == null) {
      s.perform(get("/api/v1/auth/csrf"));
    }
    String json = "{\"inviteCode\":\"" + code + "\",\"handle\":\"" + handle + "\",\"displayName\":\"" + displayName
        + "\",\"password\":\"" + password + "\"}";
    return s.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  @Test
  void aFriendJoinsWithAnInviteGetsFundedAndIsLoggedIn() {
    when(accountQueries.activeAccountId(anyLong()))
        .thenThrow(ApiException.of(ErrorCode.ACCOUNT_NOT_FOUND, "none"));
    InviteCreatedDto inv = invite(2);
    assertThat(inv.url()).isEqualTo("http://localhost:5173/join/" + inv.code());
    String h = handle("Friend");
    WebSession s = WebSession.anonymous(mvc);

    MvcResult r = signup(s, inv.code().toUpperCase(), h.toUpperCase(), "My Friend", "long-enough-password");

    assertThat(status(r)).as(body(r)).isEqualTo(201);
    assertThat((String) json(r, "$.handle")).isEqualTo(h);
    assertThat((String) json(r, "$.displayName")).isEqualTo("My Friend");
    assertThat((String) json(r, "$.kind")).isEqualTo("HUMAN");
    assertThat((String) json(r, "$.role")).isEqualTo("PLAYER");
    assertThat((String) json(r, "$.authMethod")).isEqualTo("SESSION");
    assertThat(s.sessionId()).isNotNull();
    assertThat(status(s.perform(get("/api/v1/me")))).isEqualTo(200);

    long playerId = jdbc.sql("SELECT id FROM player WHERE handle = ?").param(h).query(Long.class).single();
    InOrder order = inOrder(accountService, watchlists);
    order.verify(accountService).openInitialAccount(eq(playerId), eq(new BigDecimal("500.00")), eq(playerId));
    order.verify(watchlists).seedDefault(playerId);
    assertThat(jdbc.sql("SELECT uses FROM invite WHERE public_id = ?").param(inv.inviteId()).query(Integer.class)
        .single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'SIGNUP' AND outcome = 'OK'")
        .query(Long.class).single()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void signupErrors() {
    InviteCreatedDto inv = invite(1);
    WebSession s = WebSession.anonymous(mvc);
    assertThat(code(signup(s, "nope", handle("x"), "Name", "long-enough-password"))).isEqualTo("INVITE_INVALID");
    assertThat(code(signup(s, inv.code(), "Bad Handle!", "Name", "long-enough-password")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(signup(s, inv.code(), handle("x"), "<script>", "long-enough-password")))
        .isEqualTo("VALIDATION_FAILED");
    MvcResult weak = signup(s, inv.code(), handle("x"), "Name", "short");
    assertThat(status(weak)).isEqualTo(422);
    assertThat(code(weak)).isEqualTo("WEAK_PASSWORD");

    Fixtures.PlayerRef taken = fx.human(handle("taken"));
    MvcResult dup = signup(s, inv.code(), taken.handle(), "Name", "long-enough-password");
    assertThat(status(dup)).isEqualTo(409);
    assertThat(code(dup)).isEqualTo("HANDLE_TAKEN");
    assertThat(jdbc.sql("SELECT uses FROM invite WHERE public_id = ?").param(inv.inviteId()).query(Integer.class)
        .single()).as("a failed signup gives the invite slot back").isZero();

    assertThat(status(signup(WebSession.anonymous(mvc), inv.code(), handle("ok"), "Ok", "long-enough-password")))
        .isEqualTo(201);
    assertThat(code(signup(WebSession.anonymous(mvc), inv.code(), handle("late"), "Late", "long-enough-password")))
        .as("used up").isEqualTo("INVITE_INVALID");
  }

  @Test
  void expiredAndRevokedInvitesAreRefused() {
    InviteCreatedDto expiring = invite(5);
    InviteCreatedDto revoked = invite(5);
    invites.revoke(revoked.inviteId());
    time.advance(Duration.ofHours(25));
    assertThat(code(signup(WebSession.anonymous(mvc), expiring.code(), handle("x"), "Name", "long-enough-password")))
        .isEqualTo("INVITE_INVALID");
    assertThat(code(signup(WebSession.anonymous(mvc), revoked.code(), handle("x"), "Name", "long-enough-password")))
        .isEqualTo("INVITE_INVALID");
  }

  @Test
  void signupNeedsCsrf() {
    InviteCreatedDto inv = invite(1);
    WebSession s = WebSession.anonymous(mvc);
    s.perform(get("/api/v1/auth/csrf"));
    MvcResult r = s.performWithoutCsrf(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
        .content("{\"inviteCode\":\"" + inv.code() + "\",\"handle\":\"zz\",\"displayName\":\"Zz\",\"password\":\"x\"}"));
    assertThat(code(r)).isEqualTo("CSRF_INVALID");
  }

  @Test
  void recentAuthExpiresAndReauthRefreshesIt() {
    Fixtures.PlayerRef p = fx.human(handle("reauth"));
    WebSession s = WebSession.login(mvc, p.handle(), Fixtures.PASSWORD);
    String create = "{\"name\":\"script\"}";
    MvcResult fresh = s.perform(post("/api/v1/me/api-keys").contentType(MediaType.APPLICATION_JSON).content(create));
    assertThat(status(fresh)).as(body(fresh)).isEqualTo(201);

    time.advance(Duration.ofMinutes(16));
    MvcResult stale = s.perform(post("/api/v1/me/api-keys").contentType(MediaType.APPLICATION_JSON).content(create));
    assertThat(status(stale)).isEqualTo(403);
    assertThat(code(stale)).isEqualTo("RECENT_AUTH_REQUIRED");

    MvcResult wrong = s.perform(post("/api/v1/auth/reauth").contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"wrong-password\"}"));
    assertThat(status(wrong)).isEqualTo(401);
    assertThat(code(wrong)).isEqualTo("BAD_CREDENTIALS");

    MvcResult ok = s.perform(post("/api/v1/auth/reauth").contentType(MediaType.APPLICATION_JSON)
        .content("{\"password\":\"" + Fixtures.PASSWORD + "\"}"));
    assertThat(status(ok)).as(body(ok)).isEqualTo(204);
    MvcResult again = s.perform(post("/api/v1/me/api-keys").contentType(MediaType.APPLICATION_JSON).content(create));
    assertThat(status(again)).as(body(again)).isEqualTo(201);
  }

  @Test
  void reauthIsSessionOnly() throws Exception {
    Fixtures.PlayerRef p = fx.human(handle("keyreauth"));
    String key = fx.apiKey(p, pepper());
    MvcResult r = mvc.perform(post("/api/v1/auth/reauth").header("Authorization", "Bearer " + key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + Fixtures.PASSWORD + "\"}")).andReturn();
    assertThat(status(r)).isEqualTo(403);
    assertThat(code(r)).isEqualTo("FORBIDDEN");
  }
}
