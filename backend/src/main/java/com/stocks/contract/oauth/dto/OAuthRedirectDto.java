package com.stocks.contract.oauth.dto;

/**
 * §18.3 {@code OAuthRedirect}: where the browser goes after the consent decision (§17.9.3): the client's
 * {@code redirect_uri} with {@code code}, {@code state} and {@code iss} (or {@code error=access_denied}). The SPA
 * navigates there with {@code window.location}.
 */
public record OAuthRedirectDto(String redirectUrl) {}
