package com.stocks.contract.oauth.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code ConnectedApp}: one live OAuth grant of the signed-in human (Settings → Connected apps, §17.9.1):
 * the client, the redirect host, the player it acts as ({@code actAs}: the human or one of their bots), the granted
 * scopes, when it was approved and when it last refreshed or exchanged a token.
 */
public record ConnectedAppDto(String grantId, OAuthClientInfoDto client, String redirectHost, PlayerSummaryDto actAs,
                              List<String> scopes, Instant createdAt, Instant lastUsedAt) {}
