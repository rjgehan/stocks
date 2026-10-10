package com.stocks.identity;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import java.time.Instant;

/**
 * A {@code player} row as WS1 reads it (§8.1). {@code passwordHash} is {@code {bcrypt}…} for humans and null for AI
 * players (DB CHECK), so an AI player can never open a session (§17.4 layer 2). Never serialized to clients.
 */
public record PlayerRecord(long id, String publicId, String handle, String displayName, PlayerKind kind,
                           PlayerRole role, PlayerStatus status, String aiModel, Long ownerPlayerId,
                           String passwordHash, int failedLogins, Instant lockedUntil, String avatarSeed,
                           String preferences, boolean apiTradingAllowed, Instant createdAt) {

  @Override
  public String toString() {
    return "PlayerRecord[id=" + id + ", handle=" + handle + ", kind=" + kind + ", role=" + role + ", status=" + status
        + "]";
  }
}
