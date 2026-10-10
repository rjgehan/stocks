package com.stocks.oauth;

import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL of the authorization server (§17.9.6): pending authorization requests, grants (with their one-time code)
 * and rotating refresh tokens. Secrets arrive here already hashed. Conditional updates ({@code … IS NULL}) make
 * every one-time step (consuming a request, using a code, rotating a refresh token) safe under concurrency.
 */
@Repository
public class OAuthStore {

  private final JdbcClient jdbc;

  public OAuthStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ----------------------------------------------------------------------------------- authorization requests

  /** A pending {@code /oauth2/authorize} request. */
  public record PendingRequest(String requestId, String clientId, OAuthClientKind clientKind, String clientName,
                               String clientHost, String redirectUri, Set<String> scopes, boolean offlineAccess,
                               boolean refreshAllowed, String state, String codeChallenge, String resource, Instant createdAt,
                               Instant expiresAt, Instant consumedAt) {}

  public void insertRequest(PendingRequest r) {
    jdbc.sql("""
            INSERT INTO oauth_authorization_request (request_id, client_id, client_kind, client_name, client_host,
                   redirect_uri, scopes, offline_access, refresh_allowed, state, code_challenge, resource, created_at,
                   expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(r.requestId(), r.clientId(), r.clientKind().name(), r.clientName(), r.clientHost(), r.redirectUri(),
            joinScopes(r.scopes()), r.offlineAccess(), r.refreshAllowed(), r.state(), r.codeChallenge(), r.resource(),
            Jdbc.ts(r.createdAt()), Jdbc.ts(r.expiresAt()))
        .update();
  }

  public Optional<PendingRequest> findRequest(String requestId) {
    return jdbc.sql("""
            SELECT request_id, client_id, client_kind, client_name, client_host, redirect_uri, scopes, offline_access,
                   refresh_allowed, state, code_challenge, resource, created_at, expires_at, consumed_at
              FROM oauth_authorization_request WHERE request_id = ?""")
        .param(requestId)
        .query((rs, n) -> new PendingRequest(rs.getString("request_id"), rs.getString("client_id"),
            OAuthClientKind.valueOf(rs.getString("client_kind")), rs.getString("client_name"),
            rs.getString("client_host"), rs.getString("redirect_uri"), splitScopes(rs.getString("scopes")),
            rs.getBoolean("offline_access"), rs.getBoolean("refresh_allowed"), rs.getString("state"), rs.getString("code_challenge"),
            rs.getString("resource"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"),
            Jdbc.instant(rs, "consumed_at")))
        .optional();
  }

  /** Marks a live request consumed; false when it was already consumed, expired or unknown. */
  public boolean consumeRequest(String requestId, Instant now) {
    return jdbc.sql("""
            UPDATE oauth_authorization_request SET consumed_at = ?
             WHERE request_id = ? AND consumed_at IS NULL AND expires_at > ?""")
        .params(Jdbc.ts(now), requestId, Jdbc.ts(now))
        .update() == 1;
  }

  // ----------------------------------------------------------------------------------- grants

  /** One consent: the code and the refresh-token family hang off it. */
  public record Grant(long id, String publicId, String clientId, OAuthClientKind clientKind, String clientName,
                      String clientHost, String redirectUri, long humanPlayerId, long actorPlayerId,
                      Set<String> scopes, String resource, boolean refreshAllowed, String codeChallenge,
                      Instant codeExpiresAt, Instant codeUsedAt, Instant createdAt, Instant lastUsedAt,
                      Instant revokedAt, String revokeReason) {

    public boolean revoked() {
      return revokedAt != null;
    }
  }

  private static final String GRANT_COLUMNS = """
      g.id, g.public_id, g.client_id, g.client_kind, g.client_name, g.client_host, g.redirect_uri, g.human_player_id,
      g.actor_player_id, g.scopes, g.resource, g.refresh_allowed, g.code_challenge, g.code_expires_at, g.code_used_at,
      g.created_at, g.last_used_at, g.revoked_at, g.revoke_reason""";

  public long insertGrant(Grant g, String codeHash) {
    jdbc.sql("""
            INSERT INTO oauth_grant (public_id, client_id, client_kind, client_name, client_host, redirect_uri,
                   human_player_id, actor_player_id, scopes, resource, refresh_allowed, code_hash, code_challenge,
                   code_expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
        .params(g.publicId(), g.clientId(), g.clientKind().name(), g.clientName(), g.clientHost(), g.redirectUri(),
            g.humanPlayerId(), g.actorPlayerId(), joinScopes(g.scopes()), g.resource(), g.refreshAllowed(), codeHash,
            g.codeChallenge(), Jdbc.ts(g.codeExpiresAt()), Jdbc.ts(g.createdAt()))
        .update();
    return jdbc.sql("SELECT id FROM oauth_grant WHERE public_id = ?").param(g.publicId()).query(Long.class).single();
  }

  public Optional<Grant> grantByCodeHash(String codeHash) {
    return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM oauth_grant g WHERE g.code_hash = ?")
        .param(codeHash).query(OAuthStore::grant).optional();
  }

  public Optional<Grant> grantByPublicId(String publicId) {
    return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM oauth_grant g WHERE g.public_id = ?")
        .param(publicId).query(OAuthStore::grant).optional();
  }

  public Optional<Grant> grantById(long id) {
    return jdbc.sql("SELECT " + GRANT_COLUMNS + " FROM oauth_grant g WHERE g.id = ?")
        .param(id).query(OAuthStore::grant).optional();
  }

  /** Marks the code used; false when it was used already (a concurrent exchange won). */
  public boolean useCode(long grantId, Instant now) {
    return jdbc.sql("""
            UPDATE oauth_grant SET code_used_at = ?, last_used_at = ? WHERE id = ? AND code_used_at IS NULL""")
        .params(Jdbc.ts(now), Jdbc.ts(now), grantId)
        .update() == 1;
  }

  public void touchGrant(long grantId, Instant now) {
    jdbc.sql("UPDATE oauth_grant SET last_used_at = ? WHERE id = ?").params(Jdbc.ts(now), grantId).update();
  }

  /** Revokes a live grant; false when it was already revoked. */
  public boolean revokeGrant(long grantId, String reason, Instant now) {
    return jdbc.sql("""
            UPDATE oauth_grant SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL""")
        .params(Jdbc.ts(now), reason, grantId)
        .update() == 1;
  }

  /** The public ids of the live grants a player consented to or that act as the player. */
  public List<Grant> liveGrantsOf(long playerId) {
    return jdbc.sql("SELECT " + GRANT_COLUMNS + """
             FROM oauth_grant g
            WHERE (g.human_player_id = ? OR g.actor_player_id = ?) AND g.revoked_at IS NULL
            ORDER BY g.id""")
        .params(playerId, playerId).query(OAuthStore::grant).list();
  }

  /**
   * The human's connected apps: grants neither revoked nor still waiting for their code exchange, with a live
   * refresh token or used within {@code accessSince} (an access token may still be valid), newest first.
   */
  public List<Grant> connectedApps(long humanPlayerId, Instant now, Instant accessSince) {
    return jdbc.sql("SELECT " + GRANT_COLUMNS + """
             FROM oauth_grant g
            WHERE g.human_player_id = ? AND g.revoked_at IS NULL AND g.code_used_at IS NOT NULL
              AND (g.last_used_at > ?
                   OR EXISTS (SELECT 1 FROM oauth_refresh_token t
                               WHERE t.grant_id = g.id AND t.rotated_at IS NULL AND t.expires_at > ?))
            ORDER BY g.created_at DESC, g.id DESC""")
        .params(humanPlayerId, Jdbc.ts(accessSince), Jdbc.ts(now))
        .query(OAuthStore::grant).list();
  }

  // ----------------------------------------------------------------------------------- refresh tokens

  public record RefreshToken(String tokenHash, long grantId, Instant createdAt, Instant expiresAt, Instant rotatedAt) {}

  public void insertRefreshToken(String tokenHash, long grantId, Instant now, Instant expiresAt) {
    jdbc.sql("INSERT INTO oauth_refresh_token (token_hash, grant_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
        .params(tokenHash, grantId, Jdbc.ts(now), Jdbc.ts(expiresAt)).update();
  }

  public Optional<RefreshToken> refreshToken(String tokenHash) {
    return jdbc.sql("""
            SELECT token_hash, grant_id, created_at, expires_at, rotated_at
              FROM oauth_refresh_token WHERE token_hash = ?""")
        .param(tokenHash)
        .query((rs, n) -> new RefreshToken(rs.getString("token_hash"), rs.getLong("grant_id"),
            Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "rotated_at")))
        .optional();
  }

  /** Marks the token rotated; false when it was rotated already (a concurrent refresh won). */
  public boolean rotate(String tokenHash, Instant now) {
    return jdbc.sql("UPDATE oauth_refresh_token SET rotated_at = ? WHERE token_hash = ? AND rotated_at IS NULL")
        .params(Jdbc.ts(now), tokenHash).update() == 1;
  }

  // ----------------------------------------------------------------------------------- cleanup (§17.9.6)

  /** Deletes expired requests, stale refresh tokens, dead grants and unused DCR clients; returns the row counts. */
  public int[] cleanup(Instant now, Instant requestCutoff, Instant refreshCutoff, Instant grantCutoff,
                       Instant unusedClientCutoff, Instant idleClientCutoff) {
    int requests = jdbc.sql("DELETE FROM oauth_authorization_request WHERE expires_at < ?")
        .param(Jdbc.ts(requestCutoff)).update();
    int refresh = jdbc.sql("DELETE FROM oauth_refresh_token WHERE expires_at < ?")
        .param(Jdbc.ts(refreshCutoff)).update();
    // dead grants: revoked, or never exchanged, or without a live refresh token, for longer than the cutoff
    List<Long> dead = jdbc.sql("""
            SELECT g.id FROM oauth_grant g
             WHERE (g.revoked_at IS NOT NULL AND g.revoked_at < ?)
                OR (g.code_used_at IS NULL AND g.code_expires_at < ?)
                OR (g.revoked_at IS NULL AND g.code_used_at IS NOT NULL AND COALESCE(g.last_used_at, g.created_at) < ?
                    AND NOT EXISTS (SELECT 1 FROM oauth_refresh_token t
                                     WHERE t.grant_id = g.id AND t.rotated_at IS NULL AND t.expires_at > ?))""")
        .params(Jdbc.ts(grantCutoff), Jdbc.ts(requestCutoff), Jdbc.ts(grantCutoff), Jdbc.ts(now))
        .query(Long.class).list();
    int grants = 0;
    for (Long id : dead) {
      jdbc.sql("DELETE FROM oauth_refresh_token WHERE grant_id = ?").param(id).update();
      grants += jdbc.sql("DELETE FROM oauth_grant WHERE id = ?").param(id).update();
    }
    int clients = jdbc.sql("""
            DELETE FROM oauth_client
             WHERE (last_used_at IS NULL AND created_at < ?
                    AND NOT EXISTS (SELECT 1 FROM oauth_grant g WHERE g.client_id = oauth_client.client_id))
                OR (COALESCE(last_used_at, created_at) < ?
                    AND NOT EXISTS (SELECT 1 FROM oauth_grant g
                                     WHERE g.client_id = oauth_client.client_id AND g.revoked_at IS NULL))""")
        .params(Jdbc.ts(unusedClientCutoff), Jdbc.ts(idleClientCutoff))
        .update();
    return new int[] {requests, refresh, grants, clients};
  }

  // ----------------------------------------------------------------------------------- helpers

  static String joinScopes(Set<String> scopes) {
    return String.join(" ", new TreeSet<>(scopes));
  }

  static Set<String> splitScopes(String stored) {
    if (stored == null || stored.isBlank()) {
      return Set.of();
    }
    return Set.copyOf(new LinkedHashSet<>(Arrays.asList(stored.trim().split("\\s+"))));
  }

  private static Grant grant(ResultSet rs, int n) throws SQLException {
    return new Grant(rs.getLong("id"), rs.getString("public_id"), rs.getString("client_id"),
        OAuthClientKind.valueOf(rs.getString("client_kind")), rs.getString("client_name"),
        rs.getString("client_host"), rs.getString("redirect_uri"), rs.getLong("human_player_id"),
        rs.getLong("actor_player_id"), splitScopes(rs.getString("scopes")), rs.getString("resource"),
        rs.getBoolean("refresh_allowed"), rs.getString("code_challenge"), Jdbc.instant(rs, "code_expires_at"),
        Jdbc.instant(rs, "code_used_at"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "last_used_at"),
        Jdbc.instant(rs, "revoked_at"), rs.getString("revoke_reason"));
  }
}
