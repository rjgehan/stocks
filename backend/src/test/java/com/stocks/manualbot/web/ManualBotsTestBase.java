package com.stocks.manualbot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.WebSession;
import com.stocks.trading.testkit.TradingTestBase;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The world of the manual-bot ITs (§20.8): the WS3 test world (engine on the calling thread, scripted market, fixed
 * clock at Fri 2026-10-09 10:00 ET) with an owner, the owner's funded AI bot, another human and the admin, and one
 * quoted symbol. Requests go through the real chains as session principals with a real CSRF cookie and header.
 */
abstract class ManualBotsTestBase extends TradingTestBase {

  @Autowired JsonMapper jsonMapper;

  Fixtures.PlayerRef owner;
  Fixtures.PlayerRef bot;
  Fixtures.PlayerRef stranger;
  Fixtures.PlayerRef admin;
  PlayerPrincipal ownerP;
  PlayerPrincipal strangerP;
  PlayerPrincipal adminP;
  PlayerPrincipal botP;
  long botAccount;
  String s;

  @BeforeEach
  void manualWorld() {
    int n = next();
    owner = fx.human("mo" + n);
    bot = fx.ai("mb" + n, owner);
    accountService.openInitialAccount(bot.id(), new BigDecimal("10000.00"), owner.id());
    botAccount = accountQueries.activeAccountId(bot.id());
    stranger = fx.human("ms" + n);
    admin = fx.admin("ma" + n);
    ownerP = TestPrincipals.session(owner.id(), owner.publicId(), owner.handle());
    strangerP = TestPrincipals.session(stranger.id(), stranger.publicId(), stranger.handle());
    adminP = TestPrincipals.adminSession(admin.id(), admin.publicId(), admin.handle());
    botP = session(bot, PlayerKind.AI);
    s = symbol();
    quote(s, "99.98", 5000, "100.00", 5000);
  }

  // ------------------------------------------------------------------------------------------- requests

  /** As {@code p} on a session, with a real {@code XSRF-TOKEN} cookie and header. */
  MockHttpServletResponse as(PlayerPrincipal p, MockHttpServletRequestBuilder r) throws Exception {
    Cookie token = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie(WebSession.XSRF_COOKIE);
    return mvc.perform(r.with(TestPrincipals.as(p)).cookie(token).header(WebSession.XSRF_HEADER, token.getValue()))
        .andReturn().getResponse();
  }

  static <T> T json(MockHttpServletResponse r, String path) throws Exception {
    return JsonPath.read(r.getContentAsString(), path);
  }

  String console(Fixtures.PlayerRef b) {
    return "/api/v1/me/bots/" + b.handle();
  }

  /** A new prompt for {@code b} as the owner; its session id. */
  String newPrompt(Fixtures.PlayerRef b) throws Exception {
    MockHttpServletResponse r = as(ownerP, post(console(b) + "/manual-sessions"));
    assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
    return json(r, "$.sessionId");
  }

  MockHttpServletResponse reply(Fixtures.PlayerRef b, String sessionId, String text) throws Exception {
    return as(ownerP, post(console(b) + "/manual-sessions/" + sessionId + "/reply").contentType(MediaType.APPLICATION_JSON)
        .content(jsonMapper.writeValueAsString(Map.of("reply", text))));
  }

  MockHttpServletResponse execute(PlayerPrincipal who, Fixtures.PlayerRef b, String sessionId, Integer... rows)
      throws Exception {
    return as(who, post(console(b) + "/manual-sessions/" + sessionId + "/execute")
        .contentType(MediaType.APPLICATION_JSON)
        .content(jsonMapper.writeValueAsString(Map.of("actions", Arrays.asList(rows)))));
  }

  /** A reply: prose, then one fenced block for {@code sessionId} with the rows. */
  static String block(String sessionId, String summary, String... rows) {
    return "I looked at the account and the market.\n\n```json\n{\n  \"format\": \"stocks.manual.v1\",\n"
        + "  \"session\": \"" + sessionId + "\",\n  \"summary\": \"" + summary + "\",\n  \"orders\": [\n    "
        + Arrays.stream(rows).collect(Collectors.joining(",\n    ")) + "\n  ]\n}\n```\nThat is all.";
  }

  static String buy(String symbol, String qty) {
    return "{\"action\": \"BUY\", \"symbol\": \"" + symbol + "\", \"type\": \"MARKET\", \"qty\": \"" + qty
        + "\", \"rationale\": \"Buying " + symbol + " on strength.\"}";
  }

  static String sell(String symbol, String qty) {
    return "{\"action\": \"SELL\", \"symbol\": \"" + symbol + "\", \"type\": \"MARKET\", \"qty\": \"" + qty
        + "\", \"rationale\": \"Taking profits on " + symbol + ".\"}";
  }

  static String cancel(String orderId) {
    return "{\"action\": \"CANCEL\", \"order_id\": \"" + orderId + "\", \"rationale\": \"Stale order.\"}";
  }

  /** The bot buys {@code qty} of {@code symbol} at the market and the buy fills. */
  void botHolds(String symbol, String qty) {
    String id = orders.place(botP, market(cid(), symbol, Side.BUY, qty), meta()).order().orderId();
    time.advance(Duration.ofSeconds(1));
    quote(symbol, "99.98", 5000, "100.00", 5000);        // anti-look-ahead: the fill needs a quote after acceptance
    step();
    assertThat(orderQueries.byPublicId(botAccount, id, false).orElseThrow().status()).isEqualTo(OrderStatus.FILLED);
  }

  static List<String> codes(MockHttpServletResponse r, String path) throws Exception {
    return json(r, path);
  }
}
