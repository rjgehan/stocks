package com.stocks.manualbot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.config.StocksProperties;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.manualbot.ManualPromptBuilder;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.account.AccountCommands;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * §20.8 manual bots through the real chains, with the engine and the scripted market (H2, or embedded PostgreSQL
 * under {@code -Ppg-it}; {@code settle-wait} is PT0S): who may operate a bot, the console state and instructions,
 * prompts and the daily cap, checking a reply (previews, batch dependencies, duplicates, the reset check), executing
 * it as the bot (provenance, order of execution, idempotency, journal, audit) and the public session reads.
 */
class ManualBotsWebIT extends ManualBotsTestBase {

  @Autowired StocksProperties properties;

  // ------------------------------------------------------------------------------------------- access

  @Test
  void onlyTheOwnerOrTheAdminOperatesABot() throws Exception {
    MockHttpServletResponse state = as(ownerP, get(console(bot) + "/manual"));
    assertThat(state.getStatus()).as(state.getContentAsString()).isEqualTo(200);
    assertThat((String) json(state, "$.bot.handle")).isEqualTo(bot.handle());
    assertThat((String) json(state, "$.ownerHandle")).isEqualTo(owner.handle());
    assertThat((String) json(state, "$.instructions")).isEmpty();
    assertThat((Integer) json(state, "$.limits.maxInstructionsChars")).isEqualTo(4000);
    assertThat((String) json(state, "$.limits.expireAfter")).isEqualTo("PT24H");
    MockHttpServletResponse saved = as(ownerP, put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON)
        .content("{\"instructions\": \"Trade mega caps only.\"}"));
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    assertThat((String) json(saved, "$.instructions")).isEqualTo("Trade mega caps only.");
    assertThat((String) json(saved, "$.instructionsUpdatedBy")).isEqualTo(owner.handle());

    MockHttpServletResponse other = as(strangerP, get(console(bot) + "/manual"));
    assertThat(other.getStatus()).isEqualTo(403);
    assertThat((String) json(other, "$.code")).isEqualTo("FORBIDDEN");
    assertThat((String) json(other, "$.detail")).isEqualTo("Only @" + owner.handle() + " (or the admin) can "
        + "operate @" + bot.handle() + ".");
    assertThat(as(strangerP, post(console(bot) + "/manual-sessions")).getStatus()).isEqualTo(403);

    assertThat(as(adminP, get(console(bot) + "/manual")).getStatus()).isEqualTo(200);
    MockHttpServletResponse byAdmin = as(adminP, put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON)
        .content("{\"instructions\": \"Admin says hi.\"}"));
    assertThat(byAdmin.getStatus()).isEqualTo(200);
    assertThat((String) json(byAdmin, "$.instructionsUpdatedBy")).isEqualTo(admin.handle());

    assertThat(mvc.perform(get(console(bot) + "/manual")).andReturn().getResponse().getStatus()).isEqualTo(401);
    MockHttpServletResponse human = as(ownerP, get(console(stranger) + "/manual"));
    assertThat(human.getStatus()).isEqualTo(403);
    assertThat((String) json(human, "$.detail")).isEqualTo("Only AI bots have a manual console.");
    assertThat(as(ownerP, get("/api/v1/me/bots/nobody_here/manual")).getStatus()).isEqualTo(404);
  }

  @Test
  void keysTokensAndRequestsWithoutCsrfAreRefused() throws Exception {
    String key = fx.apiKey(bot, properties.security().apiKeyPepper());
    String ownerKey = fx.apiKey(owner, properties.security().apiKeyPepper());
    String fake = "mbs_01JABCDEFGHJKMNPQRSTVWXYZ0";
    List<Supplier<MockHttpServletRequestBuilder>> routes = List.of(
        () -> get(console(bot) + "/manual"),
        () -> put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON).content("{}"),
        () -> post(console(bot) + "/manual-sessions"),
        () -> post(console(bot) + "/manual-sessions/" + fake + "/reply").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reply\": \"x\"}"),
        () -> post(console(bot) + "/manual-sessions/" + fake + "/execute").contentType(MediaType.APPLICATION_JSON)
            .content("{\"actions\": []}"));
    PlayerPrincipal botToken = new PlayerPrincipal(bot.id(), bot.publicId(), bot.handle(), PlayerKind.AI,
        PlayerRole.PLAYER, Scopes.ALL, AuthMethod.OAUTH, null, "https://claude.ai/oauth/client", TestTimes.START);
    PlayerPrincipal ownerToken = new PlayerPrincipal(owner.id(), owner.publicId(), owner.handle(), PlayerKind.HUMAN,
        PlayerRole.PLAYER, Scopes.ALL, AuthMethod.OAUTH, null, "https://claude.ai/oauth/client", TestTimes.START);
    for (Supplier<MockHttpServletRequestBuilder> route : routes) {
      for (String token : List.of(key, ownerKey)) {
        MockHttpServletResponse r = mvc.perform(route.get().header("Authorization", "Bearer " + token)).andReturn()
            .getResponse();
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat((String) json(r, "$.code")).isEqualTo("FORBIDDEN");
      }
      for (PlayerPrincipal oauth : List.of(botToken, ownerToken)) {
        MockHttpServletResponse r = as(oauth, route.get());
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat((String) json(r, "$.code")).isEqualTo("FORBIDDEN");
      }
    }
    MockHttpServletResponse noCsrf = mvc.perform(post(console(bot) + "/manual-sessions")
        .with(TestPrincipals.as(ownerP))).andReturn().getResponse();
    assertThat(noCsrf.getStatus()).isEqualTo(403);
    assertThat((String) json(noCsrf, "$.code")).isEqualTo("CSRF_INVALID");
  }

  @Test
  void aSessionOfAnotherBotIsNotFoundOnEveryRoute() throws Exception {
    Fixtures.PlayerRef other = fx.ai("mb" + next(), owner);
    accountService.openInitialAccount(other.id(), new BigDecimal("1000.00"), owner.id());
    String foreign = newPrompt(other);
    MockHttpServletResponse r = reply(bot, foreign, block(foreign, "x", buy(s, "1")));
    assertThat(r.getStatus()).isEqualTo(404);
    assertThat((String) json(r, "$.code")).isEqualTo("NOT_FOUND");
    assertThat((String) json(r, "$.detail")).isEqualTo("No session " + foreign + " for @" + bot.handle() + ".");
    assertThat(execute(ownerP, bot, foreign).getStatus()).isEqualTo(404);
    assertThat(as(strangerP, get("/api/v1/players/" + bot.handle() + "/manual-sessions/" + foreign)).getStatus())
        .isEqualTo(404);
    assertThat(as(strangerP, get("/api/v1/players/" + other.handle() + "/manual-sessions/" + foreign)).getStatus())
        .isEqualTo(200);
  }

  // ------------------------------------------------------------------------------------------- console state

  @Test
  void instructionsAreValidatedRedactedAndTheModelLabelIsPublic() throws Exception {
    MockHttpServletResponse tooLong = as(ownerP, put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON)
        .content("{\"instructions\": \"" + "x".repeat(4001) + "\"}"));
    assertThat(tooLong.getStatus()).isEqualTo(400);
    assertThat((String) json(tooLong, "$.fields[0].field")).isEqualTo("instructions");
    MockHttpServletResponse badModel = as(ownerP, put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aiModel\": \"" + "m".repeat(25) + "\"}"));
    assertThat(badModel.getStatus()).isEqualTo(400);
    assertThat((String) json(badModel, "$.fields[0].field")).isEqualTo("aiModel");

    String body = jsonMapper.writeValueAsString(Map.of("instructions", "  Use stk_abcdef12_ABCDEFGH12345678 and "
        + "Bearer abcdefghijklmnopqrstuvwxyz012345\r\nSecond\u0007 line\t ok  ", "aiModel", "Gemini 3"));
    MockHttpServletResponse saved = as(ownerP, put(console(bot) + "/manual").contentType(MediaType.APPLICATION_JSON)
        .content(body));
    assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
    String expected = "Use [redacted] and [redacted]\nSecond line ok";
    assertThat((String) json(saved, "$.instructions")).isEqualTo(expected);
    assertThat(jdbc.sql("SELECT instructions FROM manual_bot WHERE player_id = ?").param(bot.id())
        .query(String.class).single()).isEqualTo(expected);
    assertThat((String) json(saved, "$.bot.aiModel")).isEqualTo("Gemini 3");
    MockHttpServletResponse profile = as(strangerP, get("/api/v1/players/" + bot.handle()));
    assertThat((String) json(profile, "$.player.aiModel")).isEqualTo("Gemini 3");
    String detail = jdbc.sql("SELECT detail FROM audit_log WHERE action = 'MANUAL_BOT_UPDATE' "
            + "AND actor_player_id = ? AND target_id = ?")
        .params(owner.id(), bot.publicId()).query(String.class).list().get(0);
    assertThat(detail).contains("instructionsChars").doesNotContain("Second");

    fx.apiKey(bot, properties.security().apiKeyPepper());
    jdbc.sql("""
            INSERT INTO oauth_grant (public_id, client_id, client_kind, client_name, client_host, redirect_uri,
                                     human_player_id, actor_player_id, scopes, resource, refresh_allowed, code_hash,
                                     code_challenge, code_expires_at, code_used_at, created_at)
            VALUES (?, 'https://claude.ai/client', 'CIMD', 'Claude', 'claude.ai', 'https://claude.ai/cb', ?, ?,
                    'trade', 'http://localhost:5173/mcp', TRUE, ?, 'challenge', ?, ?, ?)""")
        .params("ogr_" + next(), owner.id(), bot.id(), "hash-" + next(), Jdbc.ts(time.now()), Jdbc.ts(time.now()),
            Jdbc.ts(time.now()))
        .update();
    MockHttpServletResponse state = as(ownerP, get(console(bot) + "/manual"));
    assertThat((Integer) json(state, "$.activeApiKeys")).isEqualTo(1);
    assertThat((Integer) json(state, "$.activeConnectors")).isEqualTo(1);
  }

  // ------------------------------------------------------------------------------------------- prompts

  @Test
  void aPromptShowsTheAccountAndTheDailyCapHolds() throws Exception {
    botHolds(s, "3");
    MockHttpServletResponse r = as(ownerP, post(console(bot) + "/manual-sessions"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    String sessionId = json(r, "$.sessionId");
    assertThat(PublicId.isValid(sessionId, Prefix.MANUAL_SESSION)).isTrue();
    String prompt = json(r, "$.untrusted.prompt");
    assertThat(prompt).startsWith(ManualPromptBuilder.FRAMING).contains("@" + bot.handle())
        .contains("Session: " + sessionId).contains("| " + s + " | 3 |").contains("Market now: REGULAR session")
        .contains("\"session\": \"" + sessionId + "\"");
    assertThat((String) json(r, "$.status")).isEqualTo("PROMPTED");
    assertThat((String) json(r, "$.marketSession")).isEqualTo("REGULAR");
    assertThat((String) json(r, "$.operatorHandle")).isEqualTo(owner.handle());
    assertThat((Integer) json(r, "$.promptChars")).isEqualTo(prompt.length());
    assertThat((String) json(r, "$.promptSha256")).hasSize(64);
    assertThat((Object) json(r, "$.untrusted.reply")).isNull();

    PublicId ids = new PublicId(time);
    for (int i = 0; i < 49; i++) {
      jdbc.sql("""
              INSERT INTO manual_session (public_id, player_id, account_id, operator_player_id, status, ai_model,
                                          market_session, prompt_sha256, prompt_text, created_at)
              VALUES (?, ?, ?, ?, 'PROMPTED', 'Claude', 'REGULAR', ?, 'p', ?)""")
          .params(ids.next(Prefix.MANUAL_SESSION), bot.id(), botAccount, owner.id(), "0".repeat(64),
              Jdbc.ts(time.now()))
          .update();
    }
    assertThat((Integer) json(as(ownerP, get(console(bot) + "/manual")), "$.sessionsToday")).isEqualTo(50);
    MockHttpServletResponse capped = as(ownerP, post(console(bot) + "/manual-sessions"));
    assertThat(capped.getStatus()).isEqualTo(429);
    assertThat((String) json(capped, "$.code")).isEqualTo("RATE_LIMITED");
    assertThat(capped.getHeader("Retry-After")).isNotNull();
  }

  // ------------------------------------------------------------------------------------------- reply

  @Test
  void aReplyIsParsedAndEveryRowPreviewed() throws Exception {
    Player human = human("5000.00");
    OrderDto theirs = place(human, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.DAY, false)).order();
    String sessionId = newPrompt(bot);
    String text = block(sessionId, "Bought some.", buy(s, "1"), buy("ZZQX", "1"),
        "{\"action\": \"BUY\", \"symbol\": \"" + s + "\", \"type\": \"MARKET\", \"qty\": \"2\"}",
        cancel(theirs.orderId()));
    MockHttpServletResponse r = reply(bot, sessionId, text);
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    assertThat((String) json(r, "$.status")).isEqualTo("REPLIED");
    assertThat((String) json(r, "$.untrusted.reply")).isEqualTo(text);
    assertThat((String) json(r, "$.untrusted.summary")).isEqualTo("Bought some.");
    assertThat((List<?>) json(r, "$.issues")).isEmpty();
    assertThat((Boolean) json(r, "$.actions[0].executable")).isTrue();
    assertThat((String) json(r, "$.actions[0].estimate.price")).isNotNull();
    assertThat((String) json(r, "$.actions[0].estimate.hold")).isNotNull();
    assertThat((Integer) json(r, "$.actions[0].line")).isEqualTo(9);
    assertThat(codes(r, "$.actions[1].issues[*].code")).containsExactly("SYMBOL_NOT_FOUND");
    assertThat((Boolean) json(r, "$.actions[1].executable")).isFalse();
    assertThat(codes(r, "$.actions[2].issues[*].code")).containsExactly("RATIONALE_REQUIRED");
    assertThat(codes(r, "$.actions[3].issues[*].code")).containsExactly("ORDER_NOT_FOUND");

    MockHttpServletResponse tooLong = reply(bot, sessionId, "x".repeat(20_001));
    assertThat(tooLong.getStatus()).isEqualTo(400);
    assertThat((String) json(tooLong, "$.fields[0].field")).isEqualTo("reply");
    assertThat((String) json(tooLong, "$.detail")).isEqualTo("The reply is longer than 20,000 characters; paste "
        + "only the part with the JSON block.");
    MockHttpServletResponse tooBig = as(ownerP, post(console(bot) + "/manual-sessions/" + sessionId + "/reply")
        .contentType(MediaType.APPLICATION_JSON).content("{\"reply\": \"" + "y".repeat(170_000) + "\"}"));
    assertThat(tooBig.getStatus()).isEqualTo(400);
    assertThat((String) json(tooBig, "$.fields[0].field")).isEqualTo("body");
  }

  @Test
  void duplicateRepliesWarnAndAResetRefusesTheOldPrompt() throws Exception {
    String text = "```json\n{\"summary\": \"Same again.\", \"orders\": [" + buy(s, "1") + "]}\n```";
    String first = newPrompt(bot);
    assertThat(reply(bot, first, text).getStatus()).isEqualTo(200);
    assertThat(execute(ownerP, bot, first, 1).getStatus()).isEqualTo(200);
    String second = newPrompt(bot);
    MockHttpServletResponse again = reply(bot, second, text);
    assertThat(codes(again, "$.issues[*].code")).contains("SESSION_MISSING", "DUPLICATE_REPLY");
    assertThat((List<String>) json(again, "$.issues[?(@.code == 'DUPLICATE_REPLY')].message")).singleElement()
        .asString().contains(first);

    String third = newPrompt(bot);
    accountService.reset(bot.id(), null, "fresh start",
        new IdempotencyRef(AccountCommands.RESET_SCOPE_ADMIN, "r-" + next(), "h"), owner.id());
    MockHttpServletResponse reset = reply(bot, third, block(third, "x", buy(s, "1")));
    assertThat(reset.getStatus()).isEqualTo(400);
    assertThat((String) json(reset, "$.detail")).isEqualTo("@" + bot.handle() + " was reset since this prompt; make "
        + "a new prompt.");
  }

  // ------------------------------------------------------------------------------------------- execute

  @Test
  void executePlacesTheRowsAsTheBotCancelsFirstThenSellsThenBuys() throws Exception {
    botHolds(s, "2");
    OrderDto resting = orders.place(botP, limit(cid(), s, Side.SELL, "2", "120.00", TimeInForce.GTC, false), meta())
        .order();
    after(Duration.ofSeconds(1));
    String s2 = symbol();
    quote(s2, "49.98", 5000, "50.00", 5000);
    String sessionId = newPrompt(bot);
    String summary = "Rotating from " + s + " into " + s2 + ".";
    MockHttpServletResponse checked = reply(bot, sessionId, block(sessionId, summary, buy(s2, "1"), sell(s, "2"),
        cancel(resting.orderId())));
    assertThat(checked.getStatus()).as(checked.getContentAsString()).isEqualTo(200);
    assertThat((List<Boolean>) json(checked, "$.actions[*].executable")).containsExactly(true, true, true);
    assertThat(codes(checked, "$.actions[1].issues[*].code")).containsExactly("DEPENDS_ON_CANCEL");
    assertThat((String) json(checked, "$.actions[2].symbol")).isEqualTo(s);

    MockHttpServletResponse done = execute(ownerP, bot, sessionId, 1, 2, 3);
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat((String) json(done, "$.status")).isEqualTo("EXECUTED");
    assertThat((String) json(done, "$.executedAt")).isNotNull();
    assertThat((List<String>) json(done, "$.actions[*].result.status")).containsExactly("PLACED", "PLACED",
        "CANCELLED");
    assertThat(orderQueries.byPublicId(botAccount, resting.orderId(), false).orElseThrow().status().name())
        .isEqualTo("CANCELLED");
    String ulid = sessionId.substring(4);
    List<Map<String, Object>> placed = jdbc.sql("""
            SELECT public_id, side, symbol, channel, mcp_client, rationale_public, client_order_id
              FROM trade_order WHERE account_id = ? AND client_order_id LIKE 'mbs-%' ORDER BY id""")
        .param(botAccount).query().listOfRows();
    assertThat(placed).hasSize(2);
    assertThat(placed.get(0)).containsEntry("side", "SELL").containsEntry("symbol", s)
        .containsEntry("client_order_id", "mbs-" + ulid + "-2");
    assertThat(placed.get(1)).containsEntry("side", "BUY").containsEntry("symbol", s2)
        .containsEntry("client_order_id", "mbs-" + ulid + "-1");
    assertThat(placed).allSatisfy(o -> {
      assertThat(o).containsEntry("channel", "WEB").containsEntry("mcp_client", "manual-console " + sessionId);
      assertThat(o.get("rationale_public")).isEqualTo(true);
    });
    assertThat((String) json(done, "$.actions[0].result.orderId")).isEqualTo(placed.get(1).get("public_id"));

    String noteId = json(done, "$.journalNoteId");
    assertThat(noteId).startsWith("jnl_");
    assertThat(jdbc.sql("SELECT body FROM journal_note WHERE public_id = ? AND player_id = ?")
        .params(noteId, bot.id()).query(String.class).single()).isEqualTo(summary);
    Map<String, Object> audit = jdbc.sql("SELECT actor_player_id, actor_kind, channel, mcp_client FROM audit_log "
            + "WHERE action = 'MANUAL_SESSION_EXECUTE' AND target_id = ?")
        .param(sessionId).query().singleRow();
    assertThat(((Number) audit.get("actor_player_id")).longValue()).isEqualTo(owner.id());
    assertThat(audit).containsEntry("actor_kind", "HUMAN").containsEntry("channel", "WEB");

    // idempotent: nothing is placed twice, and the session takes no new reply
    MockHttpServletResponse again = execute(ownerP, bot, sessionId, 1, 2, 3);
    assertThat(again.getStatus()).isEqualTo(200);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ? AND client_order_id LIKE 'mbs-%'")
        .param(botAccount).query(Integer.class).single()).isEqualTo(2);
    assertThat((String) json(again, "$.journalNoteId")).isEqualTo(noteId);
    MockHttpServletResponse late = reply(bot, sessionId, block(sessionId, "x", buy(s, "1")));
    assertThat(late.getStatus()).isEqualTo(400);
    assertThat((String) json(late, "$.detail")).isEqualTo("This session already placed orders; make a new prompt.");

    // the public read sees the same session
    MockHttpServletResponse list = as(strangerP, get("/api/v1/players/" + bot.handle() + "/manual-sessions"));
    assertThat(list.getStatus()).isEqualTo(200);
    assertThat((String) json(list, "$.items[0].sessionId")).isEqualTo(sessionId);
    assertThat((Object) json(list, "$.items[0].untrusted.prompt")).isNull();
    assertThat((Object) json(list, "$.items[0].untrusted.reply")).isNull();
    assertThat((String) json(list, "$.items[0].untrusted.summary")).isEqualTo(summary);
    assertThat((List<String>) json(list, "$.items[0].actions[*].result.status")).hasSize(3);
    MockHttpServletResponse detail = as(strangerP, get("/api/v1/players/" + bot.handle() + "/manual-sessions/"
        + sessionId));
    assertThat((String) json(detail, "$.untrusted.prompt")).startsWith(ManualPromptBuilder.FRAMING);
    assertThat((String) json(detail, "$.untrusted.reply")).contains("Rotating from");
    assertThat(as(strangerP, get("/api/v1/players/nobody_here/manual-sessions")).getStatus()).isEqualTo(404);
    fx.status(bot, "DISABLED");
    assertThat(as(strangerP, get("/api/v1/players/" + bot.handle() + "/manual-sessions")).getStatus())
        .isEqualTo(404);
  }

  @Test
  void executedAtIsSetEvenWhenEveryPlacementFails() throws Exception {
    String sessionId = newPrompt(bot);
    assertThat(reply(bot, sessionId, block(sessionId, "One big buy.", buy(s, "50"))).getStatus()).isEqualTo(200);
    orders.place(botP, limit(cid(), s, Side.BUY, "90", "99.00", TimeInForce.DAY, false), meta());   // holds ~$9,000
    MockHttpServletResponse done = execute(ownerP, bot, sessionId, 1);
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat((String) json(done, "$.actions[0].result.status")).isEqualTo("FAILED");
    assertThat((String) json(done, "$.actions[0].result.code")).isEqualTo("INSUFFICIENT_BUYING_POWER");
    assertThat((String) json(done, "$.status")).isEqualTo("EXECUTED");
    assertThat(jdbc.sql("SELECT executed_at FROM manual_session WHERE public_id = ?").param(sessionId)
        .query((rs, n) -> Jdbc.instant(rs, "executed_at")).single()).isEqualTo(time.now());
    assertThat(reply(bot, sessionId, block(sessionId, "x", buy(s, "1"))).getStatus()).isEqualTo(400);
  }

  @Test
  void frozenPlayersAndExpiredPromptsCannotExecute() throws Exception {
    String sessionId = newPrompt(bot);
    assertThat(reply(bot, sessionId, block(sessionId, "One buy.", buy(s, "1"))).getStatus()).isEqualTo(200);
    fx.status(bot, "FROZEN");
    MockHttpServletResponse frozenBot = execute(ownerP, bot, sessionId, 1);
    assertThat(frozenBot.getStatus()).isEqualTo(403);
    assertThat((String) json(frozenBot, "$.code")).isEqualTo("PLAYER_FROZEN");
    MockHttpServletResponse look = as(ownerP, get(console(bot) + "/manual"));
    assertThat(look.getStatus()).isEqualTo(200);
    assertThat((String) json(look, "$.status")).isEqualTo("FROZEN");
    fx.status(bot, "ACTIVE");
    fx.status(owner, "FROZEN");
    MockHttpServletResponse frozenHuman = execute(ownerP, bot, sessionId, 1);
    assertThat(frozenHuman.getStatus()).isEqualTo(403);
    assertThat((String) json(frozenHuman, "$.code")).isEqualTo("PLAYER_FROZEN");
    fx.status(owner, "ACTIVE");
    time.advance(Duration.ofHours(25));
    MockHttpServletResponse expired = execute(ownerP, bot, sessionId, 1);
    assertThat(expired.getStatus()).isEqualTo(400);
    assertThat((String) json(expired, "$.detail")).isEqualTo("This prompt is older than 24 h; make a new one.");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM trade_order WHERE account_id = ? AND client_order_id LIKE 'mbs-%'")
        .param(botAccount).query(Integer.class).single()).isZero();
  }

  @Test
  void aFullJournalSkipsTheNoteButTheOrdersArePlaced() throws Exception {
    PublicId ids = new PublicId(time);
    for (int i = 0; i < 12; i++) {
      jdbc.sql("INSERT INTO journal_note (public_id, player_id, body, channel, created_at, visible_at) "
              + "VALUES (?, ?, 'earlier', 'MCP_KEY', ?, ?)")
          .params(ids.next(Prefix.JOURNAL_NOTE), bot.id(), Jdbc.ts(time.now().minusSeconds(3600)),
              Jdbc.ts(time.now().minusSeconds(3600)))
          .update();
    }
    String sessionId = newPrompt(bot);
    assertThat(reply(bot, sessionId, block(sessionId, "One more buy.", buy(s, "1"))).getStatus()).isEqualTo(200);
    MockHttpServletResponse done = execute(ownerP, bot, sessionId, 1);
    assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
    assertThat((String) json(done, "$.actions[0].result.status")).isEqualTo("PLACED");
    assertThat((Object) json(done, "$.journalNoteId")).isNull();
    List<String> skipped = json(done, "$.issues[?(@.code == 'JOURNAL_SKIPPED')].message");
    assertThat(skipped).singleElement().asString().startsWith("Journal note skipped: ");
  }

  @Test
  void anEmptyExecutePostsTheSummaryOnlyAndUnknownRowsAreRefused() throws Exception {
    String sessionId = newPrompt(bot);
    assertThat(execute(ownerP, bot, sessionId).getStatus()).as("no reply yet").isEqualTo(400);
    assertThat(reply(bot, sessionId, block(sessionId, "Nothing to do today.", buy("ZZQX", "1"))).getStatus())
        .isEqualTo(200);
    MockHttpServletResponse bad = execute(ownerP, bot, sessionId, 1);
    assertThat(bad.getStatus()).isEqualTo(400);
    assertThat((String) json(bad, "$.fields[0].field")).isEqualTo("actions");
    assertThat(execute(ownerP, bot, sessionId, 7).getStatus()).isEqualTo(400);
    MockHttpServletResponse summaryOnly = execute(adminP, bot, sessionId);
    assertThat(summaryOnly.getStatus()).as(summaryOnly.getContentAsString()).isEqualTo(200);
    assertThat((String) json(summaryOnly, "$.journalNoteId")).startsWith("jnl_");
    assertThat((Object) json(summaryOnly, "$.actions[0].result")).isNull();
    assertThat(jdbc.sql("SELECT actor_player_id FROM audit_log WHERE action = 'MANUAL_SESSION_EXECUTE' "
        + "AND target_id = ?").param(sessionId).query(Long.class).single()).isEqualTo(admin.id());
  }

  @Test
  void aSessionLevelErrorBlocksExecute() throws Exception {
    String sessionId = newPrompt(bot);
    MockHttpServletResponse r = reply(bot, sessionId, "I would buy " + s + " but I am not sure.");
    assertThat(r.getStatus()).isEqualTo(200);
    assertThat(codes(r, "$.issues[*].code")).containsExactly("NO_JSON_BLOCK");
    MockHttpServletResponse refused = execute(ownerP, bot, sessionId);
    assertThat(refused.getStatus()).isEqualTo(400);
    assertThat((String) json(refused, "$.detail")).isEqualTo("Fix the reply first.");
  }
}
