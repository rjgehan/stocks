package com.stocks.trading.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.WebSession;
import com.stocks.trading.testkit.TradingTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code /api/v1/order-groups} over HTTP (§18.2, §11.9) through the real security chains: a bot with an API key and a
 * human with a session (CSRF). Status codes (201 / 200 replay / 409 / 422 with the leg's field), the group JSON
 * (decimals as strings, legs with their {@code group} reference), scopes, the leg as an ordinary order, replace and
 * cancel, and another player's group being invisible.
 */
class OrderGroupApiTest extends TradingTestBase {

  static final String PEPPER = "test-pepper-not-secret-0123456789abcdef";

  @Test
  void aBotPlacesReplaysReplacesAndCancelsABracketWithItsApiKey() throws Exception {
    Player bot = bot();
    String key = fx.apiKey(bot.ref(), Scopes.ALL, PEPPER);
    String s = symbol();
    quote(s, "19.98", 300, "20.00", 300);
    String body = """
        {"clientOrderId":"grp-bot-1","type":"BRACKET","symbol":"%s","qty":"5","timeInForce":"GTC",
         "entry":{"type":"LIMIT","limitPrice":"19.50"},"takeProfit":{"limitPrice":"22.00"},
         "stopLoss":{"stopPrice":"18.00"},"rationale":"pullback entry with a defined risk"}""".formatted(s);

    MvcResult placed = bearer(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON).content(body), key);
    assertThat(placed.getResponse().getStatus()).isEqualTo(201);
    String json = WebSession.contentOf(placed);
    String groupId = JsonPath.read(json, "$.group.groupId");
    assertThat(groupId).startsWith("grp_");
    assertThat((Object) JsonPath.read(json, "$.group.status")).isEqualTo("ACTIVE");
    assertThat((Object) JsonPath.read(json, "$.group.qty")).isEqualTo("5");
    assertThat((List<Object>) JsonPath.read(json, "$.group.legs[*].group.role"))
        .containsExactly("ENTRY", "TAKE_PROFIT", "STOP_LOSS");
    assertThat((List<Object>) JsonPath.read(json, "$.group.legs[*].group.held")).containsExactly(false, true, true);
    assertThat((Object) JsonPath.read(json, "$.group.legs[0].reservedCash")).isEqualTo("97.50");
    assertThat((Object) JsonPath.read(json, "$.group.legs[2].stopPrice")).isEqualTo("18.0000");
    assertThat((Object) JsonPath.read(json, "$.group.legs[1].channel")).isEqualTo("REST_KEY");
    assertThat((Object) JsonPath.read(json, "$.group.legs[1].rationale"))
        .isEqualTo("pullback entry with a defined risk");
    assertThat((Object) JsonPath.read(json, "$.buyingPowerAfter")).isEqualTo("402.50");
    String entryId = JsonPath.read(json, "$.group.legs[0].orderId");

    MvcResult replay = bearer(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON).content(body), key);
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
    MvcResult conflict = bearer(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON)
        .content(body.replace("\"5\"", "\"4\"")), key);
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

    // each leg is an ordinary order
    MvcResult leg = bearer(get("/api/v1/orders/" + entryId), key);
    assertThat((Object) JsonPath.read(WebSession.contentOf(leg), "$.group.groupId")).isEqualTo(groupId);
    assertThat((Object) JsonPath.read(WebSession.contentOf(leg), "$.events[0].detail.role")).isEqualTo("ENTRY");
    MvcResult one = bearer(get("/api/v1/order-groups/" + groupId), key);
    assertThat(one.getResponse().getStatus()).isEqualTo(200);

    MvcResult replaced = bearer(patch("/api/v1/order-groups/" + groupId).contentType(MediaType.APPLICATION_JSON)
        .content("{\"clientOrderId\":\"grp-bot-1b\",\"takeProfit\":{\"limitPrice\":\"23.00\"}}"), key);
    assertThat(replaced.getResponse().getStatus()).isEqualTo(201);
    assertThat((Object) JsonPath.read(WebSession.contentOf(replaced), "$.group.clientOrderId")).isEqualTo("grp-bot-1b");
    assertThat((Object) JsonPath.read(WebSession.contentOf(replaced), "$.group.legs[1].limitPrice"))
        .isEqualTo("23.0000");

    MvcResult cancelled = bearer(delete("/api/v1/order-groups/" + groupId), key);
    assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
    assertThat((Object) JsonPath.read(WebSession.contentOf(cancelled), "$.status")).isEqualTo("CANCELLED");
  }

  @Test
  void rejectionsNameTheLegAndScopesApply() throws Exception {
    Player bot = bot();
    String key = fx.apiKey(bot.ref(), Scopes.ALL, PEPPER);
    String s = symbol();
    quote(s, "19.98", 300, "20.00", 300);
    MvcResult bad = bearer(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"grp-bad","type":"BRACKET","symbol":"%s","qty":"5",
         "entry":{"type":"LIMIT","limitPrice":"19.50"},"takeProfit":{"limitPrice":"19.00"},
         "stopLoss":{"stopPrice":"18.00"},"rationale":"r"}""".formatted(s)), key);
    assertThat(bad.getResponse().getStatus()).isEqualTo(422);
    assertThat((Object) JsonPath.read(WebSession.contentOf(bad), "$.code")).isEqualTo("ORDER_GROUP_INVALID");
    assertThat((Object) JsonPath.read(WebSession.contentOf(bad), "$.fields[0].field"))
        .isEqualTo("takeProfit.limitPrice");

    MvcResult preview = bearer(post("/api/v1/order-groups/preview").contentType(MediaType.APPLICATION_JSON).content("""
        {"type":"OCO","symbol":"%s","qty":"1","takeProfit":{"limitPrice":"25.00"},"stopLoss":{"stopPrice":"15.00"}}"""
        .formatted(s)), key);
    assertThat(preview.getResponse().getStatus()).isEqualTo(200);
    assertThat((Object) JsonPath.read(WebSession.contentOf(preview), "$.valid")).isEqualTo(false);
    assertThat((Object) JsonPath.read(WebSession.contentOf(preview), "$.errors[0].code"))
        .isEqualTo("SHORT_SELLING_NOT_ALLOWED");

    String readOnly = fx.apiKey(bot.ref(), Scopes.READ_ONLY, PEPPER);
    MvcResult forbidden = bearer(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"grp-ro","type":"OCO","symbol":"%s","qty":"1"}""".formatted(s)), readOnly);
    assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
    assertThat(bearer(post("/api/v1/order-groups/preview").contentType(MediaType.APPLICATION_JSON).content("""
        {"type":"OCO","symbol":"%s","qty":"1"}""".formatted(s)), readOnly).getResponse().getStatus()).isEqualTo(200);
    assertThat(bearer(get("/api/v1/order-groups/grp_01NOTFOUND"), readOnly).getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void aHumanPlacesAnOcoFromTheBrowserAndAnotherPlayerCannotSeeIt() throws Exception {
    Fixtures.PlayerRef ref = fx.human("grpweb" + next());
    accountService.openInitialAccount(ref.id(), null, ref.id());
    WebSession web = WebSession.login(mvc, ref.handle(), Fixtures.PASSWORD);
    String s = symbol();
    quote(s, "9.98", 300, "10.00", 300);
    MvcResult placed = web.perform(post("/api/v1/order-groups").contentType(MediaType.APPLICATION_JSON).content("""
        {"clientOrderId":"web-g-1","type":"OCO","side":"BUY","symbol":"%s","qty":"3",
         "takeProfit":{"limitPrice":"9.00"},"stopLoss":{"stopPrice":"11.00"}}""".formatted(s)));
    assertThat(placed.getResponse().getStatus()).isEqualTo(201);
    String groupId = JsonPath.read(WebSession.contentOf(placed), "$.group.groupId");
    assertThat((Object) JsonPath.read(WebSession.contentOf(placed), "$.group.legs[0].channel")).isEqualTo("WEB");

    Player other = bot();
    String keyB = fx.apiKey(other.ref(), Scopes.ALL, PEPPER);
    assertThat(bearer(get("/api/v1/order-groups/" + groupId), keyB).getResponse().getStatus()).isEqualTo(404);
    assertThat(bearer(delete("/api/v1/order-groups/" + groupId), keyB).getResponse().getStatus()).isEqualTo(404);
  }

  private MvcResult bearer(MockHttpServletRequestBuilder request, String token) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
  }
}
