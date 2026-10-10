package com.stocks.contract.oauth.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code POST /me/oauth/requests/{requestId}} (§17.9.3): {@code approve: false} denies; {@code approve: true} needs
 * {@code actAs} (a handle from the consent's {@code actors}) and {@code scopes} (null = every requested scope the
 * actor may get; otherwise a non-empty subset of them).
 */
public record OAuthDecisionRequest(@NotNull Boolean approve, String actAs, List<String> scopes) {}
