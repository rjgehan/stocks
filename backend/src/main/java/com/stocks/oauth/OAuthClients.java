package com.stocks.oauth;

import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.oauth.cimd.CimdClients;
import com.stocks.oauth.cimd.ClientIdUrls;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves and authenticates OAuth clients (§17.9.2): a {@code client_id} that looks like a URL is a CIMD client
 * ({@link CimdClients}), anything else a DCR registration ({@link DcrClients}). Token-endpoint authentication
 * (RFC 6749 §2.3, OAuth 2.1 §2.4): public clients send only {@code client_id}; DCR confidential clients use HTTP
 * Basic or {@code client_secret} in the form (either is accepted, the secret compared in constant time against its
 * SHA-256). A {@code client_assertion} (ChatGPT's {@code private_key_jwt}) is ignored: that method is not advertised.
 * CIMD documents are fetched at {@code /oauth2/authorize} only, never at the token or revocation endpoint.
 */
@Component
public class OAuthClients {

  private final CimdClients cimd;
  private final DcrClients dcr;
  private final OAuthProperties properties;

  public OAuthClients(CimdClients cimd, DcrClients dcr, OAuthProperties properties) {
    this.cimd = cimd;
    this.dcr = dcr;
    this.properties = properties;
  }

  /**
   * The client named {@code clientId}.
   *
   * @throws OAuthException {@code invalid_client} when it is unknown, unfetchable or invalid
   */
  public OAuthClient resolve(String clientId) {
    if (clientId == null || clientId.isBlank()) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "client_id is required.");
    }
    if (ClientIdUrls.looksLikeUrl(clientId)) {
      return cimd.resolve(clientId);
    }
    if (!properties.dcr().enabled()) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "Unknown client_id.");
    }
    return dcr.find(clientId)
        .orElseThrow(() -> new OAuthException(OAuthException.INVALID_CLIENT, "Unknown client_id."));
  }

  /** Client credentials as presented at the token or revocation endpoint. */
  public record Credentials(String clientId, String secret, boolean basic) {}

  /**
   * Reads the client's credentials from {@code Authorization: Basic} or the form ({@code client_id},
   * {@code client_secret}).
   *
   * @throws OAuthException {@code invalid_client} for a malformed Basic header, or {@code invalid_request} when
   *                        Basic and form name different clients
   */
  public static Credentials credentials(String authorization, String formClientId, String formSecret) {
    if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      String decoded;
      try {
        decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials.");
      }
      int colon = decoded.indexOf(':');
      if (colon < 0) {
        throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials.");
      }
      String id = urlDecode(decoded.substring(0, colon));
      String secret = urlDecode(decoded.substring(colon + 1));
      if (formClientId != null && !formClientId.equals(id)) {
        throw new OAuthException(OAuthException.INVALID_REQUEST, "client_id differs from the Basic credentials.");
      }
      return new Credentials(id, secret, true);
    }
    return new Credentials(formClientId, formSecret, false);
  }

  /**
   * Authenticates the client calling the token or revocation endpoint. A CIMD client is a public client whose
   * {@code client_id} only has to be a well-formed client-identifier URL here: its document is <b>not</b> fetched
   * (the code or refresh token is bound to that exact {@code client_id}, PKCE and rotation protect it), so a refresh
   * never depends on the vendor's document host being up, and anonymous token requests cannot trigger fetches.
   *
   * @throws OAuthException {@code invalid_client} (401)
   */
  public OAuthClient authenticate(Credentials credentials) {
    String clientId = credentials.clientId();
    if (ClientIdUrls.looksLikeUrl(clientId)) {
      if (!properties.cimd().enabled() || ClientIdUrls.problem(clientId) != null) {
        throw new OAuthException(OAuthException.INVALID_CLIENT, "Unknown client_id.");
      }
      String host = URI.create(clientId).getHost().toLowerCase(Locale.ROOT);
      return new OAuthClient(clientId, OAuthClientKind.CIMD, host, host, List.of(),
          Set.of(OAuthClient.GRANT_CODE, OAuthClient.GRANT_REFRESH), OAuthClient.AUTH_NONE, null);
    }
    OAuthClient client = resolve(clientId);
    if (client.isPublic()) {
      return client; // PKCE (and the refresh token itself) protect public clients
    }
    if (credentials.secret() == null || credentials.secret().isEmpty()
        || !Secrets.constantTimeEquals(Secrets.sha256Hex(credentials.secret()), client.secretHash())) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "Client authentication failed.");
    }
    return client;
  }

  private static String urlDecode(String s) {
    try {
      return URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials.");
    }
  }
}
