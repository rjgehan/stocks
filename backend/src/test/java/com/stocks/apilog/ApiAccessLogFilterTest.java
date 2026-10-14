package com.stocks.apilog;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.agents.ApiAccessRecorder;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.security.PlayerAuthenticationToken;
import com.stocks.testkit.TestPrincipals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

/** §17.10 the REST half of the API access log: what is recorded, the endpoint template and the outcome. */
class ApiAccessLogFilterTest {

  record Call(PlayerPrincipal caller, Channel channel, String endpoint, String outcome, String ip, String client) {}

  final List<Call> calls = new ArrayList<>();
  final ApiAccessRecorder recorder = (caller, channel, endpoint, outcome, ms, ip, client) ->
      calls.add(new Call(caller, channel, endpoint, outcome, ip, client));
  final PlayerPrincipal key = TestPrincipals.apiKey(7, "ply_1", "claude", PlayerKind.AI, Scopes.ALL, 3);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest r = new MockHttpServletRequest(method, path);
    r.addHeader("Authorization", "Bearer stk_x");
    r.addHeader("User-Agent", "curl/8");
    r.setRemoteAddr("10.0.0.5");
    return r;
  }

  @Test
  void keyRequestsAreRecordedWithTheRouteTemplateAndStatus() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
    MockHttpServletRequest r = request("GET", "/api/v1/orders/ord_01J9ZC7M0Q8W5T3K2R1N6B4V8X");
    MockHttpServletResponse response = new MockHttpServletResponse();
    new ApiAccessLogFilter(recorder).doFilter(r, response, (req, res) -> {
      req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/orders/{orderId}");
      ((MockHttpServletResponse) res).setStatus(404);
    });
    assertThat(calls).containsExactly(new Call(key, Channel.REST_KEY, "GET /api/v1/orders/{orderId}", "404",
        "10.0.0.5", "curl/8"));
  }

  @Test
  void aRequestThatNeverReachedMvcIsNormalized() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
    MockHttpServletResponse response = new MockHttpServletResponse();
    new ApiAccessLogFilter(recorder).doFilter(request("DELETE", "/api/v1/orders/ord_01J9ZC7M0Q8W5T3K2R1N6B4V8X"),
        response, (req, res) -> ((MockHttpServletResponse) res).setStatus(403));
    assertThat(calls).extracting(Call::endpoint, Call::outcome)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("DELETE /api/v1/orders/{id}", "403"));
  }

  @Test
  void sessionsAnonymousCallsAndMcpAreNotRecorded() throws Exception {
    new ApiAccessLogFilter(recorder).doFilter(request("GET", "/api/v1/account"), new MockHttpServletResponse(),
        new MockFilterChain());
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(
        TestPrincipals.session(1, "ply_2", "alice")));
    new ApiAccessLogFilter(recorder).doFilter(request("GET", "/api/v1/account"), new MockHttpServletResponse(),
        new MockFilterChain());
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
    new ApiAccessLogFilter(recorder).doFilter(request("POST", "/mcp"), new MockHttpServletResponse(),
        new MockFilterChain());
    MockHttpServletRequest noBearer = new MockHttpServletRequest("GET", "/api/v1/account");
    new ApiAccessLogFilter(recorder).doFilter(noBearer, new MockHttpServletResponse(), new MockFilterChain());
    assertThat(calls).isEmpty();
  }

  @Test
  void runNowsEmptyClaimPollIsNotRecorded() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
    for (int status : new int[] {204, 200}) {
      new ApiAccessLogFilter(recorder).doFilter(request("POST", "/api/v1/agent-runs/requests/claim"),
          new MockHttpServletResponse(), (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/agent-runs/requests/claim");
            ((MockHttpServletResponse) res).setStatus(status);
          });
    }
    // the trader asks every minute for each bot it serves: only a claim that found a request is the bot's call
    assertThat(calls).extracting(Call::endpoint, Call::outcome)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("POST /api/v1/agent-runs/requests/claim", "200"));
  }

  @Test
  void theTradersHeartbeatIsNotRecorded() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
    for (int status : new int[] {204, 403}) {
      new ApiAccessLogFilter(recorder).doFilter(request("POST", "/api/v1/agent-runs/requests/heartbeat"),
          new MockHttpServletResponse(), (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/agent-runs/requests/heartbeat");
            ((MockHttpServletResponse) res).setStatus(status);
          });
    }
    assertThat(calls).extracting(Call::endpoint, Call::outcome)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("POST /api/v1/agent-runs/requests/heartbeat", "403"));
  }

  @Test
  void normalizeKeepsRouteWordsAndHidesValues() {
    assertThat(ApiAccessLogFilter.normalize("/api/v1/orders/ord_01J9ZC7M0Q8W5T3K2R1N6B4V8X"))
        .isEqualTo("/api/v1/orders/{id}");
    assertThat(ApiAccessLogFilter.normalize("/api/v1/bars/AAPL")).isEqualTo("/api/v1/bars/{value}");
    assertThat(ApiAccessLogFilter.normalize("/api/v1/players/bot_42/trades"))
        .isEqualTo("/api/v1/players/{value}/trades");
    assertThat(ApiAccessLogFilter.normalize("/api/v1/account/positions/BRK.B/close"))
        .isEqualTo("/api/v1/account/positions/{value}/close");
    assertThat(ApiAccessLogFilter.normalize("//api//v1/")).isEqualTo("/api/v1");
    assertThat(ApiAccessLogFilter.normalize("")).isEqualTo("/");
  }
}
