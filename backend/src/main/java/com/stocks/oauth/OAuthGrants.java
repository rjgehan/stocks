package com.stocks.oauth;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.oauth.dto.ConnectedAppDto;
import com.stocks.contract.oauth.dto.OAuthClientInfoDto;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Grant revocation and Settings → Connected apps (§17.9.1, §17.9.4). Every revocation is audited
 * ({@code KEY}/{@code OAUTH_REVOKE}, or {@code AUTH}/{@code OAUTH_REFRESH_REUSE} for reuse detection) and published as
 * {@link OAuthGrantsRevoked}, so the chain-2 grant cache drops it at once.
 */
@Service
public class OAuthGrants {

  /** {@code oauth_grant.revoke_reason} (§17.9.6). */
  public static final String USER = "USER";
  public static final String CLIENT = "CLIENT";
  public static final String REUSE = "REUSE";
  public static final String CODE_REUSE = "CODE_REUSE";
  public static final String OWNER_CHANGED = "OWNER_CHANGED";
  public static final String PLAYER_DISABLED = "PLAYER_DISABLED";
  public static final String CREDENTIALS_CHANGED = "CREDENTIALS_CHANGED";

  private final OAuthStore store;
  private final TimeSource time;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final PlayerQueries players;
  private final OAuthProperties properties;

  public OAuthGrants(OAuthStore store, TimeSource time, AuditService audit, ApplicationEventPublisher events,
                     PlayerQueries players, OAuthProperties properties) {
    this.store = store;
    this.time = time;
    this.audit = audit;
    this.events = events;
    this.players = players;
    this.properties = properties;
  }

  /**
   * Revokes {@code grant} (no-op when it is revoked already) and audits it.
   *
   * @param by   the player who asked (Settings), or null (the client, the system)
   * @param meta the request's metadata, or null
   * @return true when this call revoked it
   */
  public boolean revoke(OAuthStore.Grant grant, String reason, PlayerPrincipal by, RequestMeta meta) {
    if (!store.revokeGrant(grant.id(), reason, time.now())) {
      return false;
    }
    AuditEntry entry = AuditEntry.of(REUSE.equals(reason) || CODE_REUSE.equals(reason) ? AuditCategory.AUTH
            : AuditCategory.KEY, REUSE.equals(reason) ? "OAUTH_REFRESH_REUSE"
            : CODE_REUSE.equals(reason) ? "OAUTH_CODE_REUSE" : "OAUTH_REVOKE",
        REUSE.equals(reason) || CODE_REUSE.equals(reason) ? AuditOutcome.DENIED : AuditOutcome.OK)
        .withMeta(meta).withTarget("oauth_grant", grant.publicId())
        .withDetail("reason", reason).withDetail("client", grant.clientId())
        .withDetail("humanPlayerId", grant.humanPlayerId()).withDetail("actorPlayerId", grant.actorPlayerId());
    entry = by != null ? entry.withActor(by)
        : entry.withActor(grant.actorPlayerId(), null);
    audit.record(entry);
    events.publishEvent(new OAuthGrantsRevoked(List.of(grant.publicId())));
    return true;
  }

  /** Revokes every live grant a player consented to or that acts as the player; returns how many. */
  public int revokeAllOf(long playerId, String reason, RequestMeta meta) {
    int n = 0;
    for (OAuthStore.Grant g : store.liveGrantsOf(playerId)) {
      n += revoke(g, reason, null, meta) ? 1 : 0;
    }
    return n;
  }

  /** {@code GET /me/connected-apps}: the human's live grants, newest first. */
  public List<ConnectedAppDto> connectedApps(long humanPlayerId) {
    Instant now = time.now();
    List<ConnectedAppDto> out = new ArrayList<>();
    for (OAuthStore.Grant g : store.connectedApps(humanPlayerId, now, now.minus(properties.accessTokenTtl()))) {
      out.add(new ConnectedAppDto(g.publicId(), clientInfo(g.clientId(), g.clientName(), g.clientKind(),
          g.clientHost()), RedirectUris.host(g.redirectUri()), players.byId(g.actorPlayerId()).orElse(null),
          new ArrayList<>(new TreeSet<>(g.scopes())), g.createdAt(), g.lastUsedAt()));
    }
    return out;
  }

  /**
   * {@code DELETE /me/connected-apps/{grantId}}: the human revokes one of their grants.
   *
   * @throws ApiException 404 {@code NOT_FOUND} when it is not theirs, unknown or already revoked
   */
  public void revokeByUser(String grantPublicId, PlayerPrincipal human, RequestMeta meta) {
    OAuthStore.Grant g = store.grantByPublicId(grantPublicId)
        .filter(x -> x.humanPlayerId() == human.playerId() && !x.revoked())
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No such connected app."));
    revoke(g, USER, human, meta);
  }

  static OAuthClientInfoDto clientInfo(String clientId, String name, OAuthClientKind kind, String host) {
    return new OAuthClientInfoDto(clientId, name, kind, host == null || host.isEmpty() ? null : host);
  }
}
