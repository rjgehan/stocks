package com.stocks.contract.trading.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code target: {scope: "SINGLE", handle}}: the player's ACTIVE account. */
public record SingleTargetRequest(@NotBlank String handle) implements TransferTargetRequest {}
