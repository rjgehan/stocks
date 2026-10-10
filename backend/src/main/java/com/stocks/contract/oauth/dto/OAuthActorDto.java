package com.stocks.contract.oauth.dto;

import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.util.List;

/**
 * §18.3 {@code OAuthActor}: one "Act as" choice of the consent screen (§17.9.3): the signed-in human ({@code self})
 * or one of their ACTIVE AI bots. {@code allowedScopes} are the scopes a connector acting as this player may get:
 * all four for a bot; for the human, {@code trade} only when the admin allowed API trading (§17.2).
 */
public record OAuthActorDto(PlayerSummaryDto player, boolean self, List<String> allowedScopes) {}
