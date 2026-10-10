package com.stocks.oauth;

import com.stocks.oauth.cimd.CimdClients;
import com.stocks.oauth.cimd.ClientIdUrls;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Resolves and authenticates OAuth clients (§17.9.2): a {@code client_id} that looks like a URL is a CIMD client
 * ({@link CimdClients}), anything else a DCR registration ({@link DcrClients}). Token-endpoint authentication
 * (RFC 6749 §2.3, OAuth 2.1 §2.4): public clients send only {@code client_id}; DCR confidential clients use HTTP
 * Basic or {@code client_secret} in the form (either is accepted, the secret compared in constant time against its
 * SHA-256). A {@code client_assertion} (ChatGPT's {@code private_key_jwt}) is ignored: that method is not advertised.
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
   * Resolves and authenticates the calling client.
   *
   * @throws OAuthException {@code invalid_client} (401)
   */
  public OAuthClient authenticate(Credentials credentials) {
    OAuthClient client = resolve(credentials.clientId());
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
      return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new OAuthException(OAuthException.INVALID_CLIENT, "Malformed Basic credentials.");
    }
  }
}
