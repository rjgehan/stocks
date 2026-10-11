package com.stocks.contract.oauth.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.time.Instant;
import java.util.List;

/**
 * §21.1 {@code AdminOAuthGrant}: one live OAuth grant of a player, for the admin's player page (§17.9): the client,
 * the redirect host, the human who approved it ({@code approvedBy}) and the player it acts as ({@code actAs}: that
 * human or one of their bots), the granted scopes, when it was approved and last used, and whether its code was
 * exchanged ({@code connected}; false while the client has not completed the sign-in).
 */
public record AdminOAuthGrantDto(String grantId, OAuthClientInfoDto client, String redirectHost,
                                 PlayerSummaryDto approvedBy, PlayerSummaryDto actAs, List<String> scopes,
                                 Instant createdAt, Instant lastUsedAt, boolean connected) {}
