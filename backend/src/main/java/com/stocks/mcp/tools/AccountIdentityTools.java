package com.stocks.mcp.tools;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RateLimitService;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.dto.AccountDto;
import io.modelcontextprotocol.common.McpTransportContext;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Identity tools (§20.3): {@code whoami}. The same contract services as REST {@code GET /me} ({@link MeQueries}) and
 * {@code GET /account} ({@link AccountQueries}). {@code whoami} needs no scope, so its {@code account} member is null
 * unless the caller holds {@code account:read} (what REST {@code GET /account} requires, §17.1).
 */
@Component
public class AccountIdentityTools implements McpToolSet {

  /** Codes that mean "no such data yet" (no active account) rather than a failure. */
  private static final Set<ErrorCode> ABSENT = Set.of(ErrorCode.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
      // TODO(WS5, M1): drop NOT_IMPLEMENTED once WS1's RateLimitService and WS3's AccountQueries replace the Day-0
      // stubs (§27.1); until then whoami answers with account/rateLimits null instead of failing.
      ErrorCode.NOT_IMPLEMENTED);

  private final MeQueries meQueries;
  private final AccountQueries accountQueries;
  private final RateLimitService rateLimits;

  public AccountIdentityTools(MeQueries meQueries, AccountQueries accountQueries, RateLimitService rateLimits) {
    this.meQueries = meQueries;
    this.accountQueries = accountQueries;
    this.rateLimits = rateLimits;
  }

  @McpTool(name = "whoami", title = "Who am I",
      description = "Returns the player this API key acts as (me: handle, kind, scopes, authMethod, accountId), "
          + "the player's active account (null until one exists, or without the account:read scope) and the "
          + "remaining rate-limit budget. Call it first to confirm your identity; then call get_rules once per "
          + "session.",
      generateOutputSchema = true,
      annotations = @McpTool.McpAnnotations(title = "Who am I", readOnlyHint = true, destructiveHint = false,
          idempotentHint = true, openWorldHint = false))
  public WhoamiDto whoami(McpTransportContext ctx) {
    PlayerPrincipal principal = CurrentPlayer.require(ctx);
    MeDto me = meQueries.me(principal);
    AccountDto account = me.accountId() == null || !principal.hasScope(Scopes.ACCOUNT_READ) ? null
        : orNull(() -> accountQueries.account(accountQueries.activeAccountId(principal.playerId())));
    WhoamiDto.WhoamiRateLimitsDto limits =
        orNull(() -> WhoamiDto.WhoamiRateLimitsDto.of(rateLimits.remaining(principal.playerId())));
    return new WhoamiDto(me, account, limits);
  }

  private static <T> T orNull(Supplier<T> lookup) {
    try {
      return lookup.get();
    } catch (ApiException e) {
      if (ABSENT.contains(e.code())) {
        return null;
      }
      throw e;
    }
  }
}
