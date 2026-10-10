package com.stocks.trading.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import com.stocks.trading.testkit.TradingTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The §18.2 order and account routes over HTTP, through the real security chains: a bot with an API key (no CSRF)
 * and a human with a session (CSRF). Checks status codes (201/200 replay/409/422), the {@code Idempotent-Replay}
 * header, decimals as JSON strings (§6.3), problem+json error codes, scopes, and the read endpoints.
 */
class TradingApiTest extends TradingTestBase {

  static final String PEPPER = "test-pepper-not-secret-0123456789abcdef";

  @Test
  void aBotPlacesReplaysListsAndCancelsOrdersWithItsApiKey() throws Exception {
    Player bot = bot();
    String key = fx.apiKey(bot.ref(), Scopes.ALL, PEPPER);
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    String body = """
        {"clientOrderId":"bot-1","symbol":"%s","side":"BUY","type":"LIMIT","qty":"2","limitPrice":"95.00",
         "timeInForce":"GTC","rationale":"mean reversion"}""".formatted(s.toLowerCase());

    MvcResult placed = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body), key);
    assertThat(placed.getResponse().getStatus()).isEqualTo(201);
    String json = WebSession.contentOf(placed);
    String orderId = JsonPath.read(json, "$.order.orderId");
    assertThat(orderId).startsWith("ord_");
    assertThat((Object) JsonPath.read(json, "$.order.qty")).isEqualTo("2");
    assertThat((Object) JsonPath.read(json, "$.order.limitPrice")).isEqualTo("95.0000");
    assertThat((Object) JsonPath.read(json, "$.order.reservedCash")).isEqualTo("190.00");
    assertThat((Object) JsonPath.read(json, "$.order.channel")).isEqualTo("REST_KEY");
    assertThat((Object) JsonPath.read(json, "$.buyingPowerAfter")).isEqualTo("310.00");
    assertThat((Object) JsonPath.read(json, "$.replayed")).isEqualTo(false);

    MvcResult replay = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body), key);
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
    assertThat((String) JsonPath.read(WebSession.contentOf(replay), "$.order.orderId")).isEqualTo(orderId);

    MvcResult conflict = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
        .content(body.replace("\"2\"", "\"3\"")), key);
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
    assertThat(conflict.getResponse().getContentType()).startsWith("application/problem+json");
    assertThat((String) JsonPath.read(WebSession.contentOf(conflict), "$.code")).isEqualTo("CLIENT_ORDER_ID_CONFLICT");

    MvcResult open = bearer(get("/api/v1/orders").param("status", "OPEN"), key);
    assertThat(open.getResponse().getStatus()).isEqualTo(200);
    List<String> ids = JsonPath.read(WebSession.contentOf(open), "$.items[*].orderId");
    assertThat(ids).containsExactly(orderId);
    assertThat((Object) JsonPath.read(WebSession.contentOf(open), "$.items[0].events")).isNull();

    MvcResult one = bearer(get("/api/v1/orders/" + orderId), key);
    assertThat((Object) JsonPath.read(WebSession.contentOf(one), "$.events[0].type")).isEqualTo("ACCEPTED");
    MvcResult byCid = bearer(get("/api/v1/orders/by-client-id/bot-1"), key);
    assertThat((String) JsonPath.read(WebSession.contentOf(byCid), "$.orderId")).isEqualTo(orderId);
    assertThat(bearer(get("/api/v1/orders/ord_01NOTFOUND"), key).getResponse().getStatus()).isEqualTo(404);

    MvcResult cancelled = bearer(delete("/api/v1/orders/" + orderId), key);
    assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
    assertThat((String) JsonPath.read(WebSession.contentOf(cancelled), "$.status")).isEqualTo("CANCELLED");
    MvcResult none = bearer(delete("/api/v1/orders").param("symbol", s), key);
    assertThat((List<Object>) JsonPath.read(WebSession.contentOf(none), "$.cancelled")).isEmpty();
  }

  @Test
  void synchronousRejectionsAreProblemsWithStableCodes() throws Exception {
    Player bot = bot();
    String key = fx.apiKey(bot.ref(), Scopes.ALL, PEPPER);
    String s = symbol();
    quote(s, "199.98", 300, "200.00", 300);
    MvcResult tooBig = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"big-1","symbol":"%s","side":"BUY","type":"MARKET","qty":"3","rationale":"r"}"""
        .formatted(s)), key);
    assertThat(tooBig.getResponse().getStatus()).isEqualTo(422);
    String json = WebSession.contentOf(tooBig);
    assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("INSUFFICIENT_BUYING_POWER");
    assertThat((String) JsonPath.read(json, "$.hint")).contains("Max 2 whole shares");

    MvcResult noRationale = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"r-1","symbol":"%s","side":"BUY","type":"MARKET","notional":"10.00"}""".formatted(s)), key);
    assertThat(noRationale.getResponse().getStatus()).isEqualTo(422);
    assertThat((String) JsonPath.read(WebSession.contentOf(noRationale), "$.code")).isEqualTo("RATIONALE_REQUIRED");

    MvcResult preview = bearer(post("/api/v1/orders/preview").contentType(MediaType.APPLICATION_JSON).content("""
        {"symbol":"%s","side":"BUY","type":"MARKET","notional":"10.00","rationale":"r"}""".formatted(s)), key);
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    assertThat((Boolean) JsonPath.read(WebSession.contentOf(preview), "$.valid")).isTrue();
    assertThat((Object) JsonPath.read(WebSession.contentOf(preview), "$.estimate.qty")).isEqualTo("0.05");

    String readOnly = fx.apiKey(bot.ref(), Scopes.READ_ONLY, PEPPER);
    MvcResult forbidden = bearer(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"ro-1","symbol":"%s","side":"BUY","type":"MARKET","qty":"1","rationale":"r"}"""
        .formatted(s)), readOnly);
    assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
    assertThat((String) JsonPath.read(WebSession.contentOf(forbidden), "$.code")).isEqualTo("FORBIDDEN_SCOPE");
    assertThat(bearer(get("/api/v1/account"), readOnly).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void aHumanTradesFromTheBrowserSessionAndReadsTheAccountPages() throws Exception {
    Fixtures.PlayerRef ref = fx.human("web" + next());
    accountService.openInitialAccount(ref.id(), null, ref.id());
    WebSession web = WebSession.login(mvc, ref.handle(), Fixtures.PASSWORD);
    String s = symbol();
    quote(s, "49.98", 300, "50.00", 300);
    MvcResult placed = web.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"web-1","symbol":"%s","side":"BUY","type":"MARKET","notional":"100.00"}""".formatted(s)));
    assertThat(placed.getResponse().getStatus()).isEqualTo(201);
    assertThat((Object) JsonPath.read(WebSession.contentOf(placed), "$.order.channel")).isEqualTo("WEB");
    time.advance(Duration.ofSeconds(1));
    quote(s, "49.98", 300, "50.00", 300);
    print(s, "50.00", 100);
    step();

    String account = WebSession.contentOf(web.perform(get("/api/v1/account")));
    assertThat((Object) JsonPath.read(account, "$.equity")).isEqualTo("500.00");
    assertThat((Object) JsonPath.read(account, "$.cash.total")).isEqualTo("400.00");
    assertThat((Object) JsonPath.read(account, "$.cash.unsettled")).isEqualTo("-100.00");
    assertThat((Object) JsonPath.read(account, "$.buyingPower")).isEqualTo("400.00");
    assertThat((Object) JsonPath.read(account, "$.type")).isEqualTo("CASH");

    String balances = WebSession.contentOf(web.perform(get("/api/v1/account/balances")));
    assertThat((Object) JsonPath.read(balances, "$.settledCash")).isEqualTo("500.00");
    assertThat((Object) JsonPath.read(balances, "$.asOfEt")).isEqualTo("2026-10-09");

    String positions = WebSession.contentOf(web.perform(get("/api/v1/account/positions")));
    assertThat((List<Object>) JsonPath.read(positions, "$[*].symbol")).containsExactly(s);
    assertThat((Object) JsonPath.read(positions, "$[0].qty")).isEqualTo("2");
    assertThat((Object) JsonPath.read(positions, "$[0].marketValue")).isEqualTo("100.00");
    MvcResult pos = web.perform(get("/api/v1/account/positions/" + s.toLowerCase()));
    assertThat(pos.getResponse().getStatus()).isEqualTo(200);
    MvcResult missing = web.perform(get("/api/v1/account/positions/NOPE"));
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);

    String acts = WebSession.contentOf(web.perform(get("/api/v1/account/activities")));
    assertThat((List<Object>) JsonPath.read(acts, "$.items[*].type")).containsExactly("FILL", "INITIAL_FUNDING");
    String exes = WebSession.contentOf(web.perform(get("/api/v1/account/executions").param("symbol", s)));
    assertThat((List<Object>) JsonPath.read(exes, "$.items[*].settleDate")).containsExactly("2026-10-13");
    String runs = WebSession.contentOf(web.perform(get("/api/v1/account/runs")));
    assertThat((List<Object>) JsonPath.read(runs, "$[*].runNo")).containsExactly(1);
    String vios = WebSession.contentOf(web.perform(get("/api/v1/account/violations")));
    assertThat((Object) JsonPath.read(vios, "$.restriction")).isEqualTo("NONE");

    MvcResult close = web.perform(post("/api/v1/account/positions/" + s + "/close")
        .contentType(MediaType.APPLICATION_JSON).content("{\"clientOrderId\":\"web-close-1\"}"));
    assertThat(close.getResponse().getStatus()).isEqualTo(201);
    assertThat((Object) JsonPath.read(WebSession.contentOf(close), "$.order.qty")).isEqualTo("2");

    MvcResult reset = web.perform(post("/api/v1/account/reset").header("Idempotency-Key", "reset-1")
        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":\"RESET\"}"));
    assertThat(reset.getResponse().getStatus()).isEqualTo(422);
    assertThat((String) JsonPath.read(WebSession.contentOf(reset), "$.code")).isEqualTo("SELF_RESET_DISABLED");
  }

  @Test
  void anotherPlayersOrderIsNotVisible() throws Exception {
    Player a = bot();
    Player b = bot();
    String keyB = fx.apiKey(b.ref(), Scopes.ALL, PEPPER);
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    String orderId = place(a, limit(cid(), s, com.stocks.common.domain.Side.BUY, "1", "9.00",
        com.stocks.common.domain.TimeInForce.GTC, false)).order().orderId();
    assertThat(bearer(get("/api/v1/orders/" + orderId), keyB).getResponse().getStatus()).isEqualTo(404);
    MvcResult del = bearer(delete("/api/v1/orders/" + orderId), keyB);
    assertThat(del.getResponse().getStatus()).isEqualTo(404);
    assertThat((String) JsonPath.read(WebSession.contentOf(del), "$.code")).isEqualTo("ORDER_NOT_FOUND");
    assertThat(order(a, orderId).status()).isEqualTo(com.stocks.common.domain.OrderStatus.PENDING);
  }

  private MvcResult bearer(MockHttpServletRequestBuilder request, String token) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
  }
}
