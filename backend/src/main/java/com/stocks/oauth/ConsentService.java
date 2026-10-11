package com.stocks.oauth;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.PlayerQueries;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.oauth.dto.OAuthActorDto;
import com.stocks.contract.oauth.dto.OAuthClientKind;
import com.stocks.contract.oauth.dto.OAuthConsentDto;
import com.stocks.contract.oauth.dto.OAuthDecisionRequest;
import com.stocks.contract.oauth.dto.OAuthRedirectDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The act-as consent (§17.9.3 steps 3 and 4). The signed-in human sees the client (name, {@code client_id} host or
 * "registered dynamically"), the redirect host, the requested scopes and the "Act as" choice: their ACTIVE AI bots
 * ({@code player.owner_player_id} = the human) and themselves. A bot may get all four scopes; the human
 * {@code trade} only with {@code player.api_trading_allowed} (the personal-key rule, §17.2). There is no admin scope,
 * so nothing admin-like is grantable (§17.4). Approving creates the {@code oauth_grant} and its one-time code;
 * either decision consumes the request. Audited as {@code KEY}/{@code OAUTH_GRANT} or {@code OAUTH_DENY}.
 *
 * <p>Approving issues a long-lived credential (a rolling 30-day refresh token), so it needs a <b>recent</b> password
 * check like {@code POST /me/api-keys} (§17.1 {@code @RecentAuth}, §17.9.3 step 4): the session's
 * {@code authenticatedAt} at most {@code stocks.security.recent-auth} old, else 403 {@code RECENT_AUTH_REQUIRED} and
 * the SPA asks for the password and retries. The rule is checked here, not with the {@code security} annotation,
 * because {@code oauth} never depends on {@code security} (§17.9.5). Denying needs no recent check.
 */
@Service
public class ConsentService {

  private final OAuthStore store;
  private final JdbcClient jdbc;
  private final PlayerQueries players;
  private final TimeSource time;
  private final OAuthIds ids;
  private final OAuthProperties properties;
  private final AuthorizationEndpoint endpoint;
  private final DcrClients dcr;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Duration recentAuth;

  public ConsentService(OAuthStore store, JdbcClient jdbc, PlayerQueries players, TimeSource time, OAuthIds ids,
                        OAuthProperties properties, AuthorizationEndpoint endpoint, DcrClients dcr,
                        AuditService audit, TransactionTemplate tx, StocksProperties stocks) {
    this.store = store;
    this.jdbc = jdbc;
    this.players = players;
    this.time = time;
    this.ids = ids;
    this.properties = properties;
    this.endpoint = endpoint;
    this.dcr = dcr;
    this.audit = audit;
    this.tx = tx;
    this.recentAuth = stocks.security().recentAuth();
  }

  /** A possible actor: the player row bits the consent needs. */
  record Candidate(long id, String handle, PlayerKind kind, boolean self, boolean apiTradingAllowed) {

    Set<String> allowedScopes() {
      return !self || apiTradingAllowed ? Scopes.ALL : Scopes.READ_ONLY;
    }
  }

  /**
   * The consent screen's data.
   *
   * @throws ApiException 404 {@code NOT_FOUND} (unknown, used or expired request), 403 {@code FORBIDDEN} (not a
   *                      human on a browser session), {@code PLAYER_FROZEN}, {@code PLAYER_DISABLED}
   */
  public OAuthConsentDto view(String requestId, PlayerPrincipal human) {
    requireHuman(human);
    OAuthStore.PendingRequest r = live(requestId);
    List<OAuthActorDto> actors = new ArrayList<>();
    for (Candidate c : candidates(human.playerId())) {
      PlayerSummaryDto summary = players.byId(c.id()).orElse(null);
      if (summary != null) {
        actors.add(new OAuthActorDto(summary, c.self(), sorted(c.allowedScopes())));
      }
    }
    return new OAuthConsentDto(r.requestId(), OAuthGrants.clientInfo(r.clientId(), r.clientName(), r.clientKind(),
        r.clientHost()), r.redirectUri(), RedirectUris.host(r.redirectUri()), RedirectUris.isLoopback(r.redirectUri()),
        sorted(r.scopes()), actors, r.expiresAt());
  }

  /**
   * Approves or denies a pending request.
   *
   * @throws ApiException 404 {@code NOT_FOUND}; 400 {@code VALIDATION_FAILED} (actAs not one of the choices, scopes
   *                      outside the request or the actor's allowed set, or none); 403 as in {@link #view}, and 403
   *                      {@code RECENT_AUTH_REQUIRED} for an approval on a session whose password check is too old
   */
  public OAuthRedirectDto decide(String requestId, OAuthDecisionRequest decision, PlayerPrincipal human,
                                 RequestMeta meta) {
    requireHuman(human);
    OAuthStore.PendingRequest r = live(requestId);
    Instant now = time.now();
    if (!Boolean.TRUE.equals(decision.approve())) {
      if (!store.consumeRequest(requestId, now)) {
        throw notFound();
      }
      audit.record(AuditEntry.of(AuditCategory.KEY, "OAUTH_DENY", AuditOutcome.OK).withActor(human).withMeta(meta)
          .withTarget("oauth_client", clip(r.clientId())).withDetail("client", r.clientId()));
      Map<String, String> p = new LinkedHashMap<>();
      p.put("error", OAuthException.ACCESS_DENIED);
      p.put("error_description", "The user denied the request.");
      p.put("state", r.state());
      return new OAuthRedirectDto(endpoint.redirect(r.redirectUri(), p));
    }
    requireRecentAuth(human, now);
    String actAs = decision.actAs() == null ? "" : decision.actAs().trim().toLowerCase(Locale.ROOT);
    Candidate actor = candidates(human.playerId()).stream().filter(c -> c.handle().equals(actAs)).findFirst()
        .orElseThrow(() -> ApiException.validation("actAs", "Choose yourself or one of your active AI bots"));
    Set<String> allowed = new LinkedHashSet<>(r.scopes());
    allowed.retainAll(actor.allowedScopes());
    Set<String> scopes;
    if (decision.scopes() == null) {
      scopes = allowed;
    } else {
      scopes = new LinkedHashSet<>();
      for (String s : decision.scopes()) {
        String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) {
          throw ApiException.validation("scopes", "'" + s + "' was not requested or is not allowed for @"
              + actor.handle());
        }
        scopes.add(v);
      }
    }
    if (scopes.isEmpty()) {
      throw ApiException.validation("scopes", "Grant at least one scope");
    }
    String code = Secrets.random();
    String grantId = ids.grant();
    OAuthStore.Grant grant = new OAuthStore.Grant(0, grantId, r.clientId(), r.clientKind(), r.clientName(),
        r.clientHost(), r.redirectUri(), human.playerId(), actor.id(), scopes, r.resource(), r.refreshAllowed(),
        r.codeChallenge(), now.plus(properties.codeTtl()), null, now, null, null, null);
    tx.executeWithoutResult(s -> {
      if (!store.consumeRequest(requestId, now)) {
        throw notFound();
      }
      store.insertGrant(grant, Secrets.sha256Hex(code));
      if (r.clientKind() == OAuthClientKind.DCR) {
        dcr.touch(r.clientId());
      }
    });
    audit.record(AuditEntry.of(AuditCategory.KEY, "OAUTH_GRANT", AuditOutcome.OK).withActor(human).withMeta(meta)
        .withTarget("oauth_grant", grantId).withDetail("client", r.clientId()).withDetail("clientName", r.clientName())
        .withDetail("actAs", actor.handle()).withDetail("actorKind", actor.kind().name())
        .withDetail("scopes", new TreeSet<>(scopes)).withDetail("redirectHost", RedirectUris.host(r.redirectUri())));
    Map<String, String> p = new LinkedHashMap<>();
    p.put("code", code);
    p.put("state", r.state());
    return new OAuthRedirectDto(endpoint.redirect(r.redirectUri(), p));
  }

  /** The "Act as" choices (§17.9.3): the human's ACTIVE AI bots by handle, then the human ("myself"). */
  List<Candidate> candidates(long humanId) {
    List<Candidate> out = new ArrayList<>(jdbc.sql("""
            SELECT id, handle, kind, api_trading_allowed FROM player
             WHERE owner_player_id = ? AND kind = 'AI' AND status = 'ACTIVE'
             ORDER BY handle""")
        .param(humanId)
        .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("handle"),
            PlayerKind.valueOf(rs.getString("kind")), false, true))
        .list());
    jdbc.sql("SELECT id, handle, kind, api_trading_allowed FROM player WHERE id = ? AND kind = 'HUMAN'")
        .param(humanId)
        .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("handle"),
            PlayerKind.valueOf(rs.getString("kind")), true, rs.getBoolean("api_trading_allowed")))
        .optional().ifPresent(out::add);
    return out;
  }

  private void requireHuman(PlayerPrincipal p) {
    if (p.authMethod() != AuthMethod.SESSION || p.kind() != PlayerKind.HUMAN) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "Only a signed-in human can connect an app.");
    }
    PlayerStatus status = players.status(p.playerId());
    if (status == PlayerStatus.FROZEN) {
      throw ApiException.of(ErrorCode.PLAYER_FROZEN, "Your player is frozen by the admin; you cannot connect apps.");
    }
    if (status == PlayerStatus.DISABLED) {
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, "Your player is disabled.");
    }
  }

  /** §17.1 {@code @RecentAuth}'s rule (mirrored: {@code oauth} cannot depend on {@code security}). */
  private void requireRecentAuth(PlayerPrincipal p, Instant now) {
    Instant at = p.authenticatedAt();
    if (p.authMethod() != AuthMethod.SESSION || at == null || at.plus(recentAuth).isBefore(now)) {
      throw ApiException.of(ErrorCode.RECENT_AUTH_REQUIRED,
              "Connecting an app needs a recent password check (within " + recentAuth.toMinutes() + " minutes).")
          .withHint("POST /api/v1/auth/reauth {\"password\": \"...\"}, then retry.");
    }
  }

  private OAuthStore.PendingRequest live(String requestId) {
    OAuthStore.PendingRequest r = requestId == null ? null : store.findRequest(requestId).orElse(null);
    if (r == null || r.consumedAt() != null || !r.expiresAt().isAfter(time.now())) {
      throw notFound();
    }
    return r;
  }

  private static ApiException notFound() {
    return ApiException.of(ErrorCode.NOT_FOUND,
        "This authorization request expired or was already used; start the connection again from the app.");
  }

  private static List<String> sorted(Set<String> scopes) {
    return new ArrayList<>(new TreeSet<>(scopes));
  }

  private static String clip(String s) {
    return s.length() <= 64 ? s : s.substring(0, 64);
  }
}
