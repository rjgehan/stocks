package com.stocks.common.events;

import com.stocks.common.domain.PlayerStatus;
import java.time.Instant;

/** A player was frozen, disabled or reactivated (§5.3, §10.5); published by WS1 after its own transaction. */
public record PlayerStatusChanged(long playerId, PlayerStatus status, Instant at) {}
