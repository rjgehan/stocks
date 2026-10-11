package com.stocks.oauth;

import com.nimbusds.jwt.SignedJWT;
import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /oauth2/token} and {@code POST /oauth2/revoke} (§17.9.4).
 * <ul>
 *   <li><b>authorization_code</b>: the code (SHA-256 lookup) must belong to the authenticated client, be unused,
 *       unexpired and its grant live; {@code redirect_uri}, when sent, must equal the authorized one;
 *       {@code BASE64URL(SHA-256(code_verifier)) == code_challenge}; {@code resource}, when sent, must name the MCP
 *       resource. A second use of a code revokes the grant ({@code CODE_REUSE}, OAuth 2.1 §4.1.3).</li>
 *   <li><b>refresh_token</b>: rotated on every use (conditional {@code UPDATE}, the new token in the same
 *       transaction). An already rotated token revokes the whole grant ({@code REUSE}) unless it comes back within
 *       {@code stocks.oauth.refresh-reuse-grace} (a client that raced two refreshes), which only fails. {@code scope}
 *       may narrow the access token, never widen it. A grant whose human or actor is DISABLED, or whose bot no longer
 *       belongs to the human, is revoked here. Every failure of a dead token is {@code invalid_grant} (Claude
 *       reconnects on it).</li>
 *   <li><b>revoke</b> (RFC 7009): a refresh token or an access token of the calling client revokes its grant
 *       ({@code CLIENT}); anything else is ignored (200).</li>
 * </ul>
 */
@Service
public class TokenEndpoint {

  static final String REFRESH_PREFIX = "ort_";

  private final OAuthStore store;
  private final OAuthGrants grants;
  private final AccessTokens tokens;
  private final OAuthUrls urls;
  private final OAuthProperties properties;
  private final TimeSource time;
  private final PlayerQueries players;
  private final JdbcClient jdbc;
  private final AuditService audit;
  private final DcrClients dcr;
  private final TransactionTemplate tx;

  public TokenEndpoint(OAuthStore store, OAuthGrants grants, AccessTokens tokens, OAuthUrls urls,
                       OAuthProperties properties, TimeSource time, PlayerQueries players, JdbcClient jdbc,
                       AuditService audit, DcrClients dcr, TransactionTemplate tx) {
    this.store = store;
    this.grants = grants;
    this.tokens = tokens;
    this.urls = urls;
    this.properties = properties;
    this.time = time;
    this.players = players;
    this.jdbc = jdbc;
    this.audit = audit;
    this.dcr = dcr;
    this.tx = tx;
  }

  /** The RFC 6749 §5.1 token response. */
  public Map<String, Object> exchangeCode(OAuthClient client, Map<String, String> form, RequestMeta meta) {
    String code = required(form, "code");
    String verifier = required(form, "code_verifier");
    OAuthStore.Grant grant = store.grantByCodeHash(Secrets.sha256Hex(code))
        .filter(g -> g.clientId().equals(client.clientId()))
        .orElseThrow(() -> invalidGrant("The authorization code is invalid."));
    Instant now = time.now();
    if (grant.codeUsedAt() != null) {
      grants.revoke(grant, OAuthGrants.CODE_REUSE, null, meta);
      throw invalidGrant("The authorization code was already used; the grant has been revoked.");
    }
    if (grant.revoked()) {
      throw invalidGrant("The grant was revoked.");
    }
    if (!grant.codeExpiresAt().isAfter(now)) {
      throw invalidGrant("The authorization code has expired.");
    }
    String redirectUri = form.get("redirect_uri");
    if (redirectUri != null && !redirectUri.equals(grant.redirectUri())) {
      throw invalidGrant("redirect_uri does not match the authorization request.");
    }
    if (!Secrets.PKCE.matcher(verifier).matches()
        || !Secrets.constantTimeEquals(Secrets.s256(verifier), grant.codeChallenge())) {
      throw invalidGrant("PKCE verification failed (code_verifier).");
    }
    checkResource(form.get("resource"));
    Players p = players(grant);
    if (p.dead() != null) {
      grants.revoke(grant, p.dead(), null, meta);
      throw invalidGrant("The player this grant acts as is no longer available.");
    }
    Set<String> scopes = allowed(grant, grant.scopes());
    Map<String, Object> response = tx.execute(s -> {
      if (!store.useCode(grant.id(), now)) {
        return null;
      }
      return issue(grant, scopes, now);
    });
    if (response == null) {
      grants.revoke(grant, OAuthGrants.CODE_REUSE, null, meta);
      throw invalidGrant("The authorization code was already used; the grant has been revoked.");
    }
    if (grant.clientKind() == OAuthClientKind.DCR) {
      dcr.touch(grant.clientId());
    }
    audit(grant, "OAUTH_TOKEN", meta, scopes);
    return response;
  }

  /** The RFC 6749 §6 refresh response (a new refresh token every time). */
  public Map<String, Object> refresh(OAuthClient client, Map<String, String> form, RequestMeta meta) {
    String presented = required(form, "refresh_token");
    String hash = Secrets.sha256Hex(presented);
    OAuthStore.RefreshToken token = store.refreshToken(hash)
        .orElseThrow(() -> invalidGrant("The refresh token is invalid."));
    OAuthStore.Grant grant = store.grantById(token.grantId())
        .filter(g -> g.clientId().equals(client.clientId()))
        .orElseThrow(() -> invalidGrant("The refresh token is invalid."));
    Instant now = time.now();
    if (grant.revoked()) {
      throw invalidGrant("The grant was revoked; authorize again.");
    }
    if (token.rotatedAt() != null) {
      if (!now.isAfter(token.rotatedAt().plus(properties.refreshReuseGrace()))) {
        throw invalidGrant("The refresh token was already used.");
      }
      grants.revoke(grant, OAuthGrants.REUSE, null, meta);
      throw invalidGrant("The refresh token was already used; the grant has been revoked.");
    }
    if (!token.expiresAt().isAfter(now)) {
      throw invalidGrant("The refresh token has expired; authorize again.");
    }
    checkResource(form.get("resource"));
    Players p = players(grant);
    if (p.dead() != null) {
      grants.revoke(grant, p.dead(), null, meta);
      throw invalidGrant("The player this grant acts as is no longer available; authorize again.");
    }
    Set<String> scopes = allowed(grant, narrow(grant.scopes(), form.get("scope")));
    Map<String, Object> response = tx.execute(s -> {
      if (!store.rotate(hash, now)) {
        return null;
      }
      store.touchGrant(grant.id(), now);
      return issue(grant, scopes, now);
    });
    if (response == null) {
      throw invalidGrant("The refresh token was already used.");
    }
    audit(grant, "OAUTH_REFRESH", meta, scopes);
    return response;
  }

  /** RFC 7009: revokes the grant of a refresh or access token issued to {@code client}; silent otherwise. */
  public void revoke(OAuthClient client, String token, RequestMeta meta) {
    if (token == null || token.isBlank()) {
      throw new OAuthException(OAuthException.INVALID_REQUEST, "token is required.");
    }
    Optional<OAuthStore.Grant> grant = Optional.empty();
    if (token.startsWith(REFRESH_PREFIX)) {
      grant = store.refreshToken(Secrets.sha256Hex(token)).flatMap(t -> store.grantById(t.grantId()));
    } else if (AccessTokens.looksLikeJws(token)) {
      try {
        SignedJWT jwt = tokens.verifySignature(token);
        Object gid = jwt.getJWTClaimsSet().getClaim("gid");
        if (gid instanceof String g) {
          grant = store.grantByPublicId(g);
        }
      } catch (JwtException | java.text.ParseException e) {
        grant = Optional.empty(); // not ours: nothing to revoke (RFC 7009 §2.2)
      }
    }
    grant.filter(g -> g.clientId().equals(client.clientId()) && !g.revoked())
        .ifPresent(g -> grants.revoke(g, OAuthGrants.CLIENT, null, meta));
  }

  private Map<String, Object> issue(OAuthStore.Grant grant, Set<String> scopes, Instant now) {
    String actor = publicId(grant.actorPlayerId());
    String human = publicId(grant.humanPlayerId());
    AccessTokens.Minted access = tokens.mint(actor, human, grant.clientId(), scopes, grant.publicId());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("access_token", access.value());
    out.put("token_type", "Bearer");
    out.put("expires_in", access.expiresIn());
    if (grant.refreshAllowed()) {
      String refresh = REFRESH_PREFIX + Secrets.random();
      store.insertRefreshToken(Secrets.sha256Hex(refresh), grant.id(), now, now.plus(properties.refreshTokenTtl()));
      out.put("refresh_token", refresh);
    }
    out.put("scope", String.join(" ", new TreeSet<>(scopes)));
    return out;
  }

  /**
   * The scopes the grant may use now (§17.9.3): acting as the human, {@code trade} needs their current
   * {@code api_trading_allowed} (the admin can turn it off after the consent, like a personal key's). Never empty.
   */
  private Set<String> allowed(OAuthStore.Grant grant, Set<String> scopes) {
    if (grant.actorPlayerId() != grant.humanPlayerId() || !scopes.contains(Scopes.TRADE)) {
      return scopes;
    }
    boolean trading = jdbc.sql("SELECT api_trading_allowed FROM player WHERE id = ?").param(grant.humanPlayerId())
        .query(Boolean.class).optional().orElse(false);
    if (trading) {
      return scopes;
    }
    Set<String> out = new LinkedHashSet<>(scopes);
    out.remove(Scopes.TRADE);
    if (out.isEmpty()) {
      throw invalidGrant("This grant's only scope (trade) is no longer allowed; authorize again.");
    }
    return out;
  }

  /** The players behind a grant, and why the grant is dead (a revoke reason) or null. */
  private record Players(String dead) {}

  private Players players(OAuthStore.Grant grant) {
    if (players.status(grant.humanPlayerId()) == PlayerStatus.DISABLED
        || players.status(grant.actorPlayerId()) == PlayerStatus.DISABLED) {
      return new Players(OAuthGrants.PLAYER_DISABLED);
    }
    if (grant.actorPlayerId() != grant.humanPlayerId()) {
      Long owner = jdbc.sql("SELECT owner_player_id FROM player WHERE id = ?").param(grant.actorPlayerId())
          .query(Long.class).optional().orElse(null);
      if (owner == null || owner != grant.humanPlayerId()) {
        return new Players(OAuthGrants.OWNER_CHANGED);
      }
    }
    return new Players(null);
  }

  private String publicId(long playerId) {
    return jdbc.sql("SELECT public_id FROM player WHERE id = ?").param(playerId).query(String.class).single();
  }

  private void checkResource(String resource) {
    if (resource != null && !urls.isResource(resource)) {
      throw new OAuthException(OAuthException.INVALID_TARGET,
          "This server issues tokens for " + urls.resource() + " only.");
    }
  }

  static Set<String> narrow(Set<String> granted, String requested) {
    if (requested == null || requested.isBlank()) {
      return granted;
    }
    Set<String> out = new LinkedHashSet<>();
    for (String s : requested.trim().split("\\s+")) {
      if (s.equals(AuthorizationEndpoint.OFFLINE_ACCESS)) {
        continue;
      }
      if (!granted.contains(s)) {
        throw new OAuthException(OAuthException.INVALID_SCOPE, "scope may only narrow the granted scopes ("
            + String.join(" ", new TreeSet<>(granted)) + ").");
      }
      out.add(s);
    }
    return out.isEmpty() ? granted : out;
  }

  private void audit(OAuthStore.Grant grant, String action, RequestMeta meta, Set<String> scopes) {
    audit.record(AuditEntry.of(AuditCategory.AUTH, action, AuditOutcome.OK)
        .withActor(grant.actorPlayerId(), null).withMeta(meta).withTarget("oauth_grant", grant.publicId())
        .withDetail("client", grant.clientId()).withDetail("scopes", new TreeSet<>(scopes)));
  }

  private static String required(Map<String, String> form, String name) {
    String v = form.get(name);
    if (v == null || v.isEmpty()) {
      throw new OAuthException(OAuthException.INVALID_REQUEST, name + " is required.");
    }
    return v;
  }

  private static OAuthException invalidGrant(String description) {
    return new OAuthException(OAuthException.INVALID_GRANT, description);
  }
}
