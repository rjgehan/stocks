package com.stocks.contract.portfolio.dto;

import com.stocks.contract.portfolio.dto.FeedReactionsDto.Reaction;
import jakarta.validation.constraints.NotNull;

/** {@code PUT /feed/{itemId}/reaction} (§16.11): {@code {reaction}}; one per player per item, replacing any other. */
public record SetReactionRequest(@NotNull Reaction reaction) {}
