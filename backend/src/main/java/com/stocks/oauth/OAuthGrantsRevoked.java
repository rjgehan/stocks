package com.stocks.oauth;

import java.util.List;

/**
 * Grants were revoked (§17.9.4): published synchronously after the revocation, so chain 2's grant cache
 * ({@code security.OAuthTokenAuthenticationProvider}) drops them and their access tokens stop on the next request.
 *
 * @param grantPublicIds the {@code gid} claims of the revoked grants
 */
public record OAuthGrantsRevoked(List<String> grantPublicIds) {

  public OAuthGrantsRevoked {
    grantPublicIds = List.copyOf(grantPublicIds);
  }
}
