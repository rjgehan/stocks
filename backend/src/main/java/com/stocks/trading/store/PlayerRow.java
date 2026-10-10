package com.stocks.trading.store;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;

/** The {@code player} columns trading needs (read-only; WS1 owns the table). */
public record PlayerRow(long id, String publicId, String handle, String displayName, PlayerKind kind, PlayerRole role,
                        PlayerStatus status) {}
