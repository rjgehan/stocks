package com.stocks.mcp.tools;

import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.RateLimitStatus;
import com.stocks.contract.trading.dto.AccountDto;

/**
 * Output of the MCP tool {@code whoami} (§20.3 #1): {@code {me: Me, account: Account, rateLimits: {readsRemaining,
 * writesRemainingMinute, writesRemainingDay}}}. MCP-only (REST has {@code GET /me}). Under §6.3 every member is
 * present: {@code account} is null until the player has an ACTIVE account (like {@code Me.accountId}).
 */
public record WhoamiDto(MeDto me, AccountDto account, WhoamiRateLimitsDto rateLimits) {

  /** The caller's remaining budget (§17.5), from {@code RateLimitService.remaining}. */
  public record WhoamiRateLimitsDto(int readsRemaining, int writesRemainingMinute, int writesRemainingDay) {

    public static WhoamiRateLimitsDto of(RateLimitStatus status) {
      return new WhoamiRateLimitsDto(status.readsRemaining(), status.writesRemainingMinute(),
          status.writesRemainingDay());
    }
  }
}
