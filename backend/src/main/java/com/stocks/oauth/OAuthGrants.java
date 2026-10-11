package com.stocks.oauth;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.oauth.dto.AdminOAuthGrantDto;
import com.stocks.contract.oauth.dto.ConnectedAppDto;
import com.stocks.contract.oauth.dto.OAuthClientInfoDto;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
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
    return revokeAllOf(playerId, reason, null, meta);
  }

  /**
   * {@link #revokeAllOf(long, String, RequestMeta)} on behalf of {@code by} (the admin's freeze with "revoke keys",
   * §10.5: audited with the admin as the actor), or of the system when null.
   */
  public int revokeAllOf(long playerId, String reason, PlayerPrincipal by, RequestMeta meta) {
    int n = 0;
    for (OAuthStore.Grant g : store.liveGrantsOf(playerId)) {
      n += revoke(g, reason, by, meta) ? 1 : 0;
    }
    return n;
  }

  /**
   * The admin turned a human's API trading off (§10.5, §17.9.3): their live grants that act as themselves lose
   * {@code trade} at once, exactly like their personal keys (a grant left without any scope is revoked as
   * {@code CREDENTIALS_CHANGED}). Grants acting as a bot keep their scopes (a bot always trades). Audited
   * {@code KEY}/{@code OAUTH_SCOPE_DROP}; the changed grants are evicted from the chain-2 cache at once.
   *
   * @return how many grants lost {@code trade} (revoked ones included)
   */
  public int dropSelfTrade(long humanPlayerId, PlayerPrincipal admin, RequestMeta meta) {
    int n = 0;
    List<String> narrowed = new ArrayList<>();
    for (OAuthStore.Grant g : store.liveGrantsOf(humanPlayerId)) {
      if (g.actorPlayerId() != humanPlayerId || g.humanPlayerId() != humanPlayerId
          || !g.scopes().contains(Scopes.TRADE)) {
        continue;
      }
      TreeSet<String> rest = new TreeSet<>(g.scopes());
      rest.remove(Scopes.TRADE);
      if (rest.isEmpty()) {
        n += revoke(g, CREDENTIALS_CHANGED, admin, meta) ? 1 : 0;
        continue;
      }
      if (store.setGrantScopes(g.id(), rest)) {
        n++;
        narrowed.add(g.publicId());
        AuditEntry entry = AuditEntry.of(AuditCategory.KEY, "OAUTH_SCOPE_DROP", AuditOutcome.OK).withMeta(meta)
            .withTarget("oauth_grant", g.publicId()).withDetail("client", g.clientId())
            .withDetail("humanPlayerId", g.humanPlayerId()).withDetail("dropped", Scopes.TRADE)
            .withDetail("scopes", rest);
        audit.record(admin != null ? entry.withActor(admin) : entry.withActor(g.actorPlayerId(), null));
      }
    }
    if (!narrowed.isEmpty()) {
      events.publishEvent(new OAuthGrantsRevoked(narrowed));
    }
    return n;
  }

  /**
   * The admin's view of a player's live grants (§21.1): those the player approved and those acting as the player
   * (a bot's grants, approved by its owner), newest first.
   */
  public List<AdminOAuthGrantDto> liveGrantsForAdmin(long playerId) {
    List<OAuthStore.Grant> live = new ArrayList<>(store.liveGrantsOf(playerId));
    live.sort((a, b) -> Long.compare(b.id(), a.id()));
    List<AdminOAuthGrantDto> out = new ArrayList<>();
    for (OAuthStore.Grant g : live) {
      out.add(new AdminOAuthGrantDto(g.publicId(), clientInfo(g.clientId(), g.clientName(), g.clientKind(),
          g.clientHost()), RedirectUris.host(g.redirectUri()), players.byId(g.humanPlayerId()).orElse(null),
          players.byId(g.actorPlayerId()).orElse(null), new ArrayList<>(new TreeSet<>(g.scopes())), g.createdAt(),
          g.lastUsedAt(), g.codeUsedAt() != null));
    }
    return out;
  }

  /**
   * {@code DELETE /admin/oauth-grants/{grantId}}: the admin revokes any live grant ({@code USER}, audited with the
   * admin as the actor).
   *
   * @throws ApiException 404 {@code NOT_FOUND} when it is unknown or already revoked
   */
  public void revokeByAdmin(String grantPublicId, PlayerPrincipal admin, RequestMeta meta) {
    OAuthStore.Grant g = store.grantByPublicId(grantPublicId).filter(x -> !x.revoked())
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No such live OAuth grant."));
    revoke(g, USER, admin, meta);
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
