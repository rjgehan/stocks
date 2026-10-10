package com.stocks.testkit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.security.PlayerAuthenticationToken;
import java.util.Set;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authenticated MockMvc requests without a login round-trip: a {@link PlayerPrincipal} wrapped in the same
 * {@link PlayerAuthenticationToken} the real chains build, so scopes, {@code ROLE_ADMIN} and session-only rules apply
 * exactly as in production. Use {@link WebSession} when the test is about login, cookies or CSRF.
 *
 * <pre>{@code
 * mvc.perform(get("/api/v1/me").with(TestPrincipals.as(TestPrincipals.session(alice.id(), alice.publicId(), "alice"))));
 * }</pre>
 */
public final class TestPrincipals {

  private TestPrincipals() {}

  /** A human PLAYER on a browser session (all scopes). */
  public static PlayerPrincipal session(long playerId, String publicId, String handle) {
    return new PlayerPrincipal(playerId, publicId, handle, PlayerKind.HUMAN, PlayerRole.PLAYER, Scopes.ALL,
        AuthMethod.SESSION, null, null, TestTimes.START);
  }

  /** A human ADMIN on a browser session. */
  public static PlayerPrincipal adminSession(long playerId, String publicId, String handle) {
    return new PlayerPrincipal(playerId, publicId, handle, PlayerKind.HUMAN, PlayerRole.ADMIN, Scopes.ALL,
        AuthMethod.SESSION, null, null, TestTimes.START);
  }

  /** A player authenticated by API key {@code apiKeyId} with {@code scopes}. */
  public static PlayerPrincipal apiKey(long playerId, String publicId, String handle, PlayerKind kind,
                                       Set<String> scopes, long apiKeyId) {
    return new PlayerPrincipal(playerId, publicId, handle, kind, PlayerRole.PLAYER, scopes, AuthMethod.API_KEY,
        apiKeyId, null, TestTimes.START);
  }

  /** The MockMvc post-processor for {@code principal}. */
  public static RequestPostProcessor as(PlayerPrincipal principal) {
    return authentication(new PlayerAuthenticationToken(principal));
  }

  public static RequestPostProcessor as(Fixtures.PlayerRef player) {
    return as(session(player.id(), player.publicId(), player.handle()));
  }
}
