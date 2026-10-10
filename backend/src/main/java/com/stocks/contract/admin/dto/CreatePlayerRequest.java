package com.stocks.contract.admin.dto;

import com.stocks.common.domain.PlayerKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * §21.1 {@code CreatePlayerRequest}. {@code password}: HUMAN, required. {@code aiModel}, {@code ownerHandle} (default
 * the admin) and {@code issueKey} (default true): AI. {@code startingCash} default {@code league.defaultStartingCash}.
 */
public record CreatePlayerRequest(@NotNull PlayerKind kind, @NotBlank String handle, @NotBlank String displayName,
                                  String password, String aiModel, String ownerHandle, BigDecimal startingCash,
                                  Boolean issueKey) {}
