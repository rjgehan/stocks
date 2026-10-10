package com.stocks.contract.oauth.dto;

import java.time.Instant;
import java.util.List;

/**
 * §18.3 {@code OAuthConsent}: {@code GET /me/oauth/requests/{requestId}}, the data of the act-as consent screen
 * (§17.9.3). {@code redirectHost} must be shown (MCP); {@code loopbackRedirect} asks for the warning that any program
 * on this computer could receive the code. {@code requestedScopes} are the API scopes the client asked for (all four
 * when it named none). {@code actors}: the human's ACTIVE AI bots first, then the human ({@code self: true}).
 */
public record OAuthConsentDto(String requestId, OAuthClientInfoDto client, String redirectUri, String redirectHost,
                              boolean loopbackRedirect, List<String> requestedScopes, List<OAuthActorDto> actors,
                              Instant expiresAt) {}
