package com.stocks.contract.oauth.dto;

/**
 * §18.3 {@code OAuthClientInfo}: the OAuth client as the consent screen and Connected apps show it (§17.9.3).
 * {@code host} is the host of a CIMD {@code client_id} URL (e.g. {@code claude.ai}), null for a DCR client (shown as
 * "registered dynamically"). {@code name} is the client's own {@code client_name} (untrusted, ≤ 100 characters).
 */
public record OAuthClientInfoDto(String clientId, String name, OAuthClientKind kind, String host) {}
