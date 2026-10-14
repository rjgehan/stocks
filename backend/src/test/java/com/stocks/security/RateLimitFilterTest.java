package com.stocks.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.PlayerKind;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.testkit.TestPrincipals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** What counts as a player's activity ("last seen", the key's "last used"): not Run now's empty claim poll (§20.9). */
class RateLimitFilterTest {

  final ActivityRecorder activity = mock(ActivityRecorder.class);
  final RateLimitServiceImpl limits = mock(RateLimitServiceImpl.class);   // disabled: only the activity is left
  final RateLimitFilter filter = new RateLimitFilter(limits, activity, null);
  final PlayerPrincipal key = TestPrincipals.apiKey(7, "ply_1", "claude", PlayerKind.AI, Scopes.ALL, 3);

  @BeforeEach
  void signIn() {
    when(limits.enabled()).thenReturn(false);
    SecurityContextHolder.getContext().setAuthentication(new PlayerAuthenticationToken(key));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  void call(String method, String path, int status) throws Exception {
    MockHttpServletRequest r = new MockHttpServletRequest(method, path);
    r.setRemoteAddr("10.0.0.5");
    filter.doFilter(r, new MockHttpServletResponse(), (req, res) -> ((MockHttpServletResponse) res).setStatus(status));
  }

  @Test
  void anEmptyClaimPollIsNotTheBotsActivity() throws Exception {
    call("POST", "/api/v1/agent-runs/requests/claim", 204);         // the trader's poll, every minute
    verifyNoInteractions(activity);
    call("POST", "/api/v1/agent-runs/requests/claim", 200);         // it claimed a request: the bot runs
    verify(activity).seen(key, "10.0.0.5");
    call("GET", "/api/v1/account", 204);                            // any other route counts, whatever it answers
    verify(activity, times(2)).seen(key, "10.0.0.5");
  }

  @Test
  void theTradersHeartbeatIsNotTheBotsActivity() throws Exception {
    call("POST", "/api/v1/agent-runs/requests/heartbeat", 204);     // busy or no free slot: still served
    verifyNoInteractions(activity);
    call("POST", "/api/v1/agent-runs/requests/heartbeat", 403);     // a refused call is recorded like any other
    verify(activity).seen(key, "10.0.0.5");
  }
}
