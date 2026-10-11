package com.stocks.oauth;

import com.stocks.contract.oauth.dto.OAuthClientKind;
import java.util.List;
import java.util.Set;

/**
 * An OAuth client as the authorization server knows it (§17.9.2): a CIMD client fetched from its {@code client_id}
 * URL (never stored), or a DCR registration ({@code oauth_client}).
 *
 * @param clientId     the HTTPS URL (CIMD) or {@code ocl_<ULID>} (DCR)
 * @param name         {@code client_name} (CIMD: the URL's host when the document has none)
 * @param host         the host of the {@code client_id} URL (CIMD), or the empty string (DCR)
 * @param redirectUris the registered redirect URIs that pass {@link RedirectUris#isAllowed}
 * @param grantTypes   ⊆ {@code authorization_code refresh_token}
 * @param authMethod   {@code none}, {@code client_secret_basic} or {@code client_secret_post}
 * @param secretHash   hex SHA-256 of the client secret (DCR confidential clients only), else null
 */
public record OAuthClient(String clientId, OAuthClientKind kind, String name, String host, List<String> redirectUris,
                          Set<String> grantTypes, String authMethod, String secretHash) {

  public static final String AUTH_NONE = "none";
  public static final String AUTH_BASIC = "client_secret_basic";
  public static final String AUTH_POST = "client_secret_post";
  public static final String GRANT_CODE = "authorization_code";
  public static final String GRANT_REFRESH = "refresh_token";

  public OAuthClient {
    redirectUris = List.copyOf(redirectUris);
    grantTypes = Set.copyOf(grantTypes);
  }

  /** A public client (PKCE only, no secret). */
  public boolean isPublic() {
    return AUTH_NONE.equals(authMethod);
  }

  /** Refresh tokens are issued when the client registered the {@code refresh_token} grant type. */
  public boolean allowsRefresh() {
    return grantTypes.contains(GRANT_REFRESH);
  }
}
