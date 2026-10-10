package com.stocks.contract.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import io.modelcontextprotocol.common.McpTransportContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentPlayerTest {

  static final PlayerPrincipal BOT = new PlayerPrincipal(7L, "ply_01J9", "claude", PlayerKind.AI, PlayerRole.PLAYER,
      Set.of(Scopes.MARKET_READ, Scopes.TRADE), AuthMethod.API_KEY, 3L, null, Instant.parse("2026-10-09T14:00:00Z"));

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void readsThePrincipalFromTheSecurityContext() {
    assertThat(CurrentPlayer.current()).isEmpty();
    assertThatThrownBy(CurrentPlayer::require)
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));

    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(BOT, null, List.of()));
    assertThat(CurrentPlayer.require()).isSameAs(BOT);
    assertThat(CurrentPlayer.require().hasScope(Scopes.TRADE)).isTrue();
    assertThat(CurrentPlayer.require().hasScope(Scopes.ACCOUNT_READ)).isFalse();
    assertThat(CurrentPlayer.require().isAi()).isTrue();
    assertThat(CurrentPlayer.require().isAdmin()).isFalse();
  }

  @Test
  void ignoresOtherPrincipalsAndUnauthenticatedTokens() {
    SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice", "pw"));
    assertThat(CurrentPlayer.current()).isEmpty();
    TestingAuthenticationToken unauthenticated = new TestingAuthenticationToken(BOT, "pw");
    unauthenticated.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(unauthenticated);
    assertThat(CurrentPlayer.current()).isEmpty();
  }

  @Test
  void mcpCallsReadTheTransportContextFirst() {
    RequestMeta meta = new RequestMeta("req_1", Channel.MCP_KEY, "127.0.0.1", "agent", "claude-code", "whoami");
    McpTransportContext ctx = CurrentPlayer.mcpContext(BOT, meta);
    assertThat(CurrentPlayer.require(ctx)).isSameAs(BOT);
    assertThat(CurrentPlayer.requestMeta(ctx)).contains(meta);

    assertThat(CurrentPlayer.current(McpTransportContext.EMPTY)).isEmpty();
    assertThat(CurrentPlayer.requestMeta(McpTransportContext.EMPTY)).isEmpty();
    assertThatThrownBy(() -> CurrentPlayer.require((McpTransportContext) null)).isInstanceOf(ApiException.class);

    // falls back to the security context (e.g. a tool running on the servlet thread)
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(BOT, null, List.of()));
    assertThat(CurrentPlayer.require(McpTransportContext.EMPTY)).isSameAs(BOT);
  }

  @Test
  void principalSurvivesJavaSerializationLikeASpringSession() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var out = new java.io.ObjectOutputStream(bytes)) {
      out.writeObject(BOT);
    }
    try (var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
      assertThat(in.readObject()).isEqualTo(BOT);
    }
  }

  @Test
  void principalScopesAreImmutable() {
    PlayerPrincipal p = new PlayerPrincipal(1L, "ply_1", "a", PlayerKind.HUMAN, PlayerRole.ADMIN, null,
        AuthMethod.SESSION, null, null, Instant.EPOCH);
    assertThat(p.scopes()).isEmpty();
    assertThat(p.isAdmin()).isTrue();
    assertThat(Scopes.ALL).containsExactlyInAnyOrder("market:read", "account:read", "trade", "league:read");
    assertThat(Scopes.READ_ONLY).doesNotContain(Scopes.TRADE);
  }
}
