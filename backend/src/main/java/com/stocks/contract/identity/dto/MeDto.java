package com.stocks.contract.identity.dto;

import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import java.time.Instant;
import java.util.List;

/** §18.3 {@code Me}: {@code GET /me}, login and signup responses, MCP {@code whoami.me}. */
public record MeDto(String playerId, String handle, String displayName, PlayerKind kind, String aiModel,
                    PlayerRole role, PlayerStatus status, String accountId, int runNo, PreferencesDto preferences,
                    AuthMethod authMethod, List<String> scopes, Instant createdAt) {}
