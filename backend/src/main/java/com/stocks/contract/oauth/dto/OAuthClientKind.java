package com.stocks.contract.oauth.dto;

/**
 * §17.9.2 how the authorization server knows an OAuth client: {@code CIMD} (its {@code client_id} is the HTTPS URL of
 * a client ID metadata document) or {@code DCR} (registered dynamically through {@code POST /oauth2/register}).
 */
public enum OAuthClientKind { CIMD, DCR }
