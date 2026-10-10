package com.stocks.contract.admin.dto;

import com.stocks.contract.identity.dto.ApiKeyCreatedDto;

/** §21.1 {@code CreatePlayerResponse}. {@code apiKey} is null unless a key was issued. */
public record CreatePlayerResponse(AdminPlayerDto player, ApiKeyCreatedDto apiKey) {}
