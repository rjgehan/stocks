package com.stocks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.config.stubs.NotImplementedStub;
import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.RateLimitStatus;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.testkit.TestPrincipals;
import com.stocks.testkit.TestTimes;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/** §20.3 {@code whoami}: the caller comes from the transport context; Day-0 stubs leave optional members null. */
class AccountIdentityToolsTest {

  static final PlayerPrincipal BOT = TestPrincipals.apiKey(42, "ply_01", "claude", PlayerKind.AI, Scopes.ALL, 9);

  MeQueries meService = mock(MeQueries.class);

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
  }

  static MeDto me(String accountId) {
    return new MeDto("ply_01", "claude", "Claude", PlayerKind.AI, "Claude", PlayerRole.PLAYER, PlayerStatus.ACTIVE,
        accountId, accountId == null ? 0 : 1, null, AuthMethod.API_KEY, List.copyOf(Scopes.ALL), TestTimes.START);
  }

  @Test
  void withTheDayZeroStubsAccountAndRateLimitsAreNull() {
    when(meService.me(any())).thenReturn(me("acc_01"));
    AccountIdentityTools tools = new AccountIdentityTools(meService,
        NotImplementedStub.of(AccountQueries.class, "WS3"), NotImplementedStub.of(RateLimitService.class, "WS1"));

    WhoamiDto result = tools.whoami(CurrentPlayer.mcpContext(BOT, null));

    assertThat(result.me().handle()).isEqualTo("claude");
    assertThat(result.account()).isNull();
    assertThat(result.rateLimits()).isNull();
  }

  @Test
  void aPlayerWithoutAnAccountIsNotLookedUp() {
    when(meService.me(BOT)).thenReturn(me(null));
    AccountQueries accounts = mock(AccountQueries.class);
    RateLimitService limits = mock(RateLimitService.class);
    when(limits.remaining(42)).thenReturn(new RateLimitStatus(120, 20, 300, TestTimes.START));

    WhoamiDto result = new AccountIdentityTools(meService, accounts, limits).whoami(CurrentPlayer.mcpContext(BOT, null));

    assertThat(result.account()).isNull();
    assertThat(result.rateLimits()).isEqualTo(new WhoamiDto.WhoamiRateLimitsDto(120, 20, 300));
    org.mockito.Mockito.verifyNoInteractions(accounts);
  }

  @Test
  void withoutAccountReadTheAccountIsNotReturned() {
    PlayerPrincipal marketOnly = TestPrincipals.apiKey(42, "ply_01", "claude", PlayerKind.AI,
        Set.of(Scopes.MARKET_READ), 9);
    when(meService.me(marketOnly)).thenReturn(me("acc_01"));
    AccountQueries accounts = mock(AccountQueries.class);

    WhoamiDto result = new AccountIdentityTools(meService, accounts, NotImplementedStub.of(RateLimitService.class,
        "WS1")).whoami(CurrentPlayer.mcpContext(marketOnly, null));

    assertThat(result.me().accountId()).isEqualTo("acc_01");
    assertThat(result.account()).as("GET /account needs account:read (§17.1)").isNull();
    org.mockito.Mockito.verifyNoInteractions(accounts);
  }

  @Test
  void otherFailuresPropagate() {
    when(meService.me(BOT)).thenReturn(me("acc_01"));
    AccountQueries accounts = mock(AccountQueries.class);
    when(accounts.activeAccountId(42)).thenThrow(ApiException.of(ErrorCode.ENGINE_BUSY, "busy"));
    AccountIdentityTools tools = new AccountIdentityTools(meService, accounts,
        NotImplementedStub.of(RateLimitService.class, "WS1"));
    assertThatThrownBy(() -> tools.whoami(CurrentPlayer.mcpContext(BOT, null)))
        .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.ENGINE_BUSY);
  }

  @Test
  void noCallerIsUnauthenticated() {
    AccountIdentityTools tools = new AccountIdentityTools(meService,
        NotImplementedStub.of(AccountQueries.class, "WS3"), NotImplementedStub.of(RateLimitService.class, "WS1"));
    assertThatThrownBy(() -> tools.whoami(McpTransportContext.EMPTY)).isInstanceOf(ApiException.class)
        .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.UNAUTHENTICATED);
  }
}
