package com.stocks.admin;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.NotImplemented;
import com.stocks.common.events.PlayerStatusChanged;
import com.stocks.common.money.Money;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.contract.admin.dto.AdminPlayerDto;
import com.stocks.contract.admin.dto.AdminPlayerLiveViewDto;
import com.stocks.contract.admin.dto.AdminResetRequest;
import com.stocks.contract.admin.dto.CreatePlayerRequest;
import com.stocks.contract.admin.dto.CreatePlayerResponse;
import com.stocks.contract.admin.dto.FreezePlayerRequest;
import com.stocks.contract.admin.dto.UpdatePlayerRequest;
import com.stocks.contract.identity.dto.ApiKeyCreatedDto;
import com.stocks.contract.identity.dto.CreateApiKeyRequest;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.OrderFilter;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderStatusFilter;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.identity.AccountProvisioner;
import com.stocks.identity.ApiKeyService;
import com.stocks.identity.PlayerQueriesImpl;
import com.stocks.identity.PlayerRecord;
import com.stocks.identity.PlayerService;
import com.stocks.identity.SettingKey;
import com.stocks.security.ApiKeyAuthenticationProvider;
import com.stocks.security.PlayerSessions;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admin's player workflows (§10.5, §21.1). Player state (status, role, keys, sessions, caches) is WS1's and
 * changes first, in one transaction, followed by {@code PlayerStatusChanged}; the account side (cancel orders,
 * {@code FROZEN} restriction, reset) is then <b>one</b> engine command through WS3's {@link AccountService}. While the
 * trading core is still a Day-0 stub the engine step is skipped (logged), so the WS1 half always works.
 *
 * <p>Safety rails: an admin can neither freeze, disable nor demote themselves (no lock-out of the last admin), and an
 * AI player can never become ADMIN (also a DB CHECK, §17.4).
 */
@Service
public class AdminPlayerService {

  private static final Logger log = LoggerFactory.getLogger(AdminPlayerService.class);

  static final String RESET_SCOPE = "admin.reset";

  private final PlayerService players;
  private final PlayerQueriesImpl playerQueries;
  private final AdminPlayerViews views;
  private final ApiKeyService apiKeys;
  private final ApiKeyAuthenticationProvider keyCache;
  private final PlayerSessions sessions;
  private final AccountProvisioner accounts;
  private final AccountService accountService;
  private final AccountQueries accountQueries;
  private final OrderQueries orderQueries;
  private final IdempotencyFastPath idempotency;
  private final AuditService audit;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final TimeSource time;

  public AdminPlayerService(PlayerService players, PlayerQueriesImpl playerQueries, AdminPlayerViews views,
                            ApiKeyService apiKeys, ApiKeyAuthenticationProvider keyCache, PlayerSessions sessions,
                            AccountProvisioner accounts, AccountService accountService, AccountQueries accountQueries,
                            OrderQueries orderQueries, IdempotencyFastPath idempotency, AuditService audit,
                            ApplicationEventPublisher events, TransactionTemplate tx, TimeSource time) {
    this.players = players;
    this.playerQueries = playerQueries;
    this.views = views;
    this.apiKeys = apiKeys;
    this.keyCache = keyCache;
    this.sessions = sessions;
    this.accounts = accounts;
    this.accountService = accountService;
    this.accountQueries = accountQueries;
    this.orderQueries = orderQueries;
    this.idempotency = idempotency;
    this.audit = audit;
    this.events = events;
    this.tx = tx;
    this.time = time;
  }

  // ------------------------------------------------------------------------------------------- create

  /** {@code POST /admin/players}: the player, the funded account, the default watchlist and (AI) a key. */
  public CreatePlayerResponse create(CreatePlayerRequest req, PlayerPrincipal admin, RequestMeta meta) {
    BigDecimal startingCash = startingCash(req.startingCash());
    String displayName = req.displayName() == null ? null : req.displayName().trim();
    record Created(PlayerRecord player, ApiKeyService.IssuedKey key) {}
    Created created;
    if (req.kind() == PlayerKind.HUMAN) {
      if (req.aiModel() != null) {
        throw ApiException.validation("aiModel", "Only AI players have a model");
      }
      if (req.ownerHandle() != null) {
        throw ApiException.validation("ownerHandle", "Only AI players have an owner");
      }
      if (req.password() == null || req.password().isEmpty()) {
        throw ApiException.validation("password", "Required for a HUMAN player");
      }
      created = new Created(players.createHuman(req.handle(), displayName, req.password(), PlayerRole.PLAYER), null);
    } else {
      if (req.password() != null) {
        throw ApiException.validation("password", "AI players never have a password (they use API keys)");
      }
      long ownerId = admin.playerId();
      if (req.ownerHandle() != null && !req.ownerHandle().isBlank()) {
        PlayerRecord owner = players.byHandle(req.ownerHandle())
            .orElseThrow(() -> ApiException.validation("ownerHandle", "No such player"));
        if (owner.kind() != PlayerKind.HUMAN) {
          throw ApiException.validation("ownerHandle", "The owner of an AI player must be a human");
        }
        ownerId = owner.id();
      }
      long owner = ownerId;
      boolean issueKey = req.issueKey() == null || req.issueKey();
      created = tx.execute(status -> {
        PlayerRecord p = players.createAi(req.handle(), displayName, req.aiModel(), owner);
        ApiKeyService.IssuedKey key = issueKey ? apiKeys.issue(p.id(), "bot", Scopes.ALL, null, admin.playerId())
            : null;
        return new Created(p, key);
      });
    }
    PlayerRecord p = created.player();
    String accountOutcome;
    try {
      accountOutcome = accounts.ensureAccount(p.id(), admin.playerId(), startingCash).name();
    } catch (RuntimeException e) {
      log.warn("Admin create '{}': the first account could not be opened now ({}); it opens on the next boot",
          p.handle(), e.toString());
      accountOutcome = "FAILED";
    }
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_CREATE", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("player", p.publicId()).withDetail("handle", p.handle()).withDetail("kind", p.kind().name())
        .withDetail("startingCash", startingCash == null ? null : startingCash.toPlainString())
        .withDetail("account", accountOutcome));
    ApiKeyCreatedDto keyDto = null;
    if (created.key() != null) {
      audit.record(AuditEntry.of(AuditCategory.KEY, "KEY_ISSUE", AuditOutcome.OK).withActor(admin).withMeta(meta)
          .withTarget("api_key", created.key().keyId()).withDetail("player", p.handle())
          .withDetail("scopes", new TreeSet<>(created.key().scopes())));
      keyDto = apiKeys.created(created.key());
    }
    return new CreatePlayerResponse(views.player(p.id()), keyDto);
  }

  private static BigDecimal startingCash(BigDecimal requested) {
    if (requested == null) {
      return null;
    }
    BigDecimal v = Money.require("startingCash", requested);
    if (v.compareTo(BigDecimal.ONE) < 0 || v.compareTo(SettingKey.MAX_CASH) > 0) {
      throw ApiException.validation("startingCash", "Between 1.00 and " + SettingKey.MAX_CASH.toPlainString());
    }
    return v;
  }

  // ------------------------------------------------------------------------------------------- update

  /** {@code PATCH /admin/players/{handle}}; null members stay unchanged. */
  public AdminPlayerDto update(String handle, UpdatePlayerRequest req, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    Map<String, Object> changes = new LinkedHashMap<>();
    String displayName = req.displayName() == null ? null : req.displayName().trim();
    if (displayName != null) {
      PlayerService.validateDisplayName(displayName);
      if (!displayName.equals(p.displayName())) {
        changes.put("displayName", displayName);
      }
    }
    String aiModel = null;
    if (req.aiModel() != null) {
      if (p.kind() != PlayerKind.AI) {
        throw ApiException.validation("aiModel", "Only AI players have a model");
      }
      aiModel = PlayerService.validateAiModel(req.aiModel());
      if (!aiModel.equals(p.aiModel())) {
        changes.put("aiModel", aiModel);
      }
    }
    PlayerStatus status = req.status();
    if (status != null) {
      if (status == PlayerStatus.FROZEN) {
        throw ApiException.validation("status", "ACTIVE or DISABLED; use POST /freeze to freeze");
      }
      if (status != p.status()) {
        selfGuard(p, admin, "disable or re-enable");
        changes.put("status", status.name());
      }
    }
    PlayerRole role = req.role();
    if (role != null && role != p.role()) {
      if (role == PlayerRole.ADMIN && p.kind() != PlayerKind.HUMAN) {
        throw ApiException.of(ErrorCode.FORBIDDEN, "Only a human can be an admin (§17.4).");
      }
      selfGuard(p, admin, "change the role of");
      changes.put("role", role.name());
    }
    Boolean apiTrading = req.apiTradingAllowed();
    if (apiTrading != null) {
      if (p.kind() != PlayerKind.HUMAN) {
        throw ApiException.validation("apiTradingAllowed", "Only for humans; AI keys always trade");
      }
      if (apiTrading != p.apiTradingAllowed()) {
        changes.put("apiTradingAllowed", apiTrading);
      }
    }
    if (changes.isEmpty()) {
      return views.player(p.id());
    }
    String newAiModel = aiModel;
    tx.executeWithoutResult(s -> {
      if (changes.containsKey("displayName")) {
        players.updateDisplayName(p.id(), displayName);
      }
      if (changes.containsKey("aiModel")) {
        players.setAiModel(p.id(), newAiModel);
      }
      if (changes.containsKey("role")) {
        players.setRole(p.id(), role);
        sessions.deleteAll(p.publicId()); // ROLE_ADMIN is fixed at login (§10.5)
      }
      if (changes.containsKey("apiTradingAllowed")) {
        players.setApiTradingAllowed(p.id(), apiTrading);
        if (!apiTrading) {
          changes.put("keysWithoutTrade", apiKeys.dropScope(p.id(), Scopes.TRADE, admin.playerId()));
        }
      }
      if (changes.containsKey("status")) {
        players.setStatus(p.id(), status);
        if (status != PlayerStatus.ACTIVE) {
          sessions.deleteAll(p.publicId());
        }
      }
    });
    playerQueries.evict(p.id());
    keyCache.evictPlayer(p.id());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_UPDATE", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("player", p.publicId()).withDetail("handle", p.handle()).withDetail("changes", changes));
    if (changes.containsKey("status")) {
      events.publishEvent(new PlayerStatusChanged(p.id(), status, time.now()));
      if (status == PlayerStatus.DISABLED) {
        engineStep(p, () -> accountService.restrict(p.id(), AccountRestriction.FROZEN, CancelReason.PLAYER_DISABLED,
            "Disabled by the admin", admin.playerId()));
      } else {
        engineStep(p, () -> accountService.liftAdminRestriction(p.id(), admin.playerId()));
      }
    }
    return views.player(p.id());
  }

  // ------------------------------------------------------------------------------------------- freeze

  /** {@code POST /admin/players/{handle}/freeze}: the kill switch (§10.5). */
  public AdminPlayerDto freeze(String handle, FreezePlayerRequest req, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    String reason = AdminText.require(req.reason(), "reason", 1, 80);
    selfGuard(p, admin, "freeze");
    if (p.status() == PlayerStatus.DISABLED) {
      throw ApiException.validation("status", "This player is disabled; re-enable it before freezing");
    }
    boolean revokeKeys = Boolean.TRUE.equals(req.revokeKeys());
    int[] revoked = {0};
    tx.executeWithoutResult(s -> {
      players.setStatus(p.id(), PlayerStatus.FROZEN);
      sessions.deleteAll(p.publicId());
      if (revokeKeys) {
        revoked[0] = apiKeys.revokeAll(p.id(), admin.playerId());
      }
    });
    playerQueries.evict(p.id());
    keyCache.evictPlayer(p.id());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_FREEZE", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("player", p.publicId()).withDetail("handle", p.handle()).withDetail("reason", reason)
        .withDetail("keysRevoked", revoked[0]));
    events.publishEvent(new PlayerStatusChanged(p.id(), PlayerStatus.FROZEN, time.now()));
    engineStep(p, () -> accountService.restrict(p.id(), AccountRestriction.FROZEN, CancelReason.PLAYER_FROZEN,
        reason, admin.playerId()));
    return views.player(p.id());
  }

  /**
   * {@code POST /admin/players/{handle}/unfreeze}: back to ACTIVE; the engine lifts the admin restriction. A frozen
   * admin (who may still log in, §17.3) cannot unfreeze themselves, and a DISABLED player is re-enabled with
   * {@code PATCH status: ACTIVE}, not here (both {@code VALIDATION_FAILED}/{@code FORBIDDEN}, mirroring freeze).
   */
  public AdminPlayerDto unfreeze(String handle, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    selfGuard(p, admin, "unfreeze");
    if (p.status() == PlayerStatus.DISABLED) {
      throw ApiException.validation("status", "This player is disabled; re-enable it with PATCH status ACTIVE");
    }
    players.setStatus(p.id(), PlayerStatus.ACTIVE);
    playerQueries.evict(p.id());
    keyCache.evictPlayer(p.id());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_UNFREEZE", AuditOutcome.OK).withActor(admin)
        .withMeta(meta).withTarget("player", p.publicId()).withDetail("handle", p.handle())
        .withDetail("from", p.status().name()));
    events.publishEvent(new PlayerStatusChanged(p.id(), PlayerStatus.ACTIVE, time.now()));
    engineStep(p, () -> accountService.liftAdminRestriction(p.id(), admin.playerId()));
    return views.player(p.id());
  }

  // ------------------------------------------------------------------------------------------- password

  /** {@code POST /admin/players/{handle}/password}: humans; ends their sessions and revokes their personal keys. */
  public void setPassword(String handle, String newPassword, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    int[] revoked = {0};
    tx.executeWithoutResult(s -> {
      players.setPassword(p, newPassword);
      if (p.id() != admin.playerId()) {
        sessions.deleteAll(p.publicId());
      }
      revoked[0] = apiKeys.revokeAll(p.id(), admin.playerId());
    });
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_PASSWORD_RESET", AuditOutcome.OK).withActor(admin)
        .withMeta(meta).withTarget("player", p.publicId()).withDetail("handle", p.handle())
        .withDetail("keysRevoked", revoked[0]));
  }

  // ------------------------------------------------------------------------------------------- reset

  /**
   * {@code POST /admin/players/{handle}/reset} + {@code Idempotency-Key}: run n+1 through WS3's
   * {@code ResetAccountCmd} (§10.5, §17.6). A stored response for the key is replayed without enqueuing anything.
   */
  public ResponseEntity<AccountDto> reset(String handle, AdminResetRequest req, String idempotencyKey,
                                          PlayerPrincipal admin, RequestMeta meta) {
    String key = IdempotencyKeys.require(idempotencyKey);
    PlayerRecord p = players.requireByHandle(handle);
    String memo = AdminText.require(req.memo(), "memo", 1, 200);
    BigDecimal startingCash = startingCash(req.startingCash());
    String hash = IdempotencyKeys.requestHash(RESET_SCOPE, p.publicId(),
        startingCash == null ? "" : startingCash.toPlainString(), memo);
    var replay = idempotency.replay(RESET_SCOPE, key, hash, AccountDto.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    AccountDto account = accountService.reset(p.id(), startingCash, memo, new IdempotencyRef(RESET_SCOPE, key, hash),
        admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.ACCOUNT, "ACCOUNT_RESET", AuditOutcome.OK).withActor(admin)
        .withMeta(meta).withTarget("player", p.publicId()).withDetail("handle", p.handle()).withDetail("memo", memo)
        .withDetail("startingCash", startingCash == null ? null : startingCash.toPlainString())
        .withDetail("accountId", account == null ? null : account.accountId()));
    return ResponseEntity.ok(account);
  }

  // ------------------------------------------------------------------------------------------- live view

  /**
   * {@code POST /admin/players/{handle}/live-view}: the audited break-glass view (§16.7, §21.3). The
   * {@code ADMIN_VIEW} account event the player sees is written <b>before</b> anything is shown; if it cannot be
   * written, nothing is shown.
   */
  public AdminPlayerLiveViewDto liveView(String handle, String rawReason, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    String reason = AdminText.require(rawReason, "reason", 5, 200);
    long accountId = accountQueries.activeAccountId(p.id());
    accountService.recordAdminView(accountId, reason, admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "PLAYER_LIVE_VIEW", AuditOutcome.OK).withActor(admin)
        .withMeta(meta).withTarget("player", p.publicId()).withDetail("handle", p.handle())
        .withDetail("reason", reason));
    List<PositionDto> positions = accountQueries.positions(accountId);
    List<OrderDto> open = orderQueries.list(accountId, new OrderFilter(OrderStatusFilter.OPEN, null, null, null),
        null, 200).items();
    PlayerSummaryDto summary = playerQueries.byId(p.id()).orElseThrow();
    return new AdminPlayerLiveViewDto(summary, time.now(), reason, positions, open);
  }

  // ------------------------------------------------------------------------------------------- keys

  /** {@code POST /admin/players/{handle}/api-keys}: all four scopes by default for a bot (§17.2). */
  public ApiKeyCreatedDto issueKey(String handle, CreateApiKeyRequest req, PlayerPrincipal admin, RequestMeta meta) {
    PlayerRecord p = players.requireByHandle(handle);
    Set<String> defaults = Scopes.ALL;
    if (p.kind() == PlayerKind.HUMAN) {
      Set<String> human = new LinkedHashSet<>(Scopes.READ_ONLY);
      if (p.apiTradingAllowed()) {
        human.add(Scopes.TRADE);
      }
      defaults = Set.copyOf(human);
    }
    Set<String> scopes = ApiKeyService.parseScopes(req.scopes(), defaults);
    if (p.kind() == PlayerKind.HUMAN && scopes.contains(Scopes.TRADE) && !p.apiTradingAllowed()) {
      throw ApiException.of(ErrorCode.FORBIDDEN_SCOPE, "This human may not trade through API keys.")
          .withField("scopes", ErrorCode.FORBIDDEN_SCOPE, "'trade' needs apiTradingAllowed")
          .withHint("PATCH the player with apiTradingAllowed: true first.");
    }
    ApiKeyService.IssuedKey key = apiKeys.issue(p.id(), req.name(), scopes, apiKeys.expiry(req.expiresInDays()),
        admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.KEY, "KEY_ISSUE", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("api_key", key.keyId()).withDetail("player", p.handle())
        .withDetail("scopes", new TreeSet<>(scopes)));
    return apiKeys.created(key);
  }

  /** {@code DELETE /admin/api-keys/{keyId}}: any player's key, effective immediately. */
  public void revokeKey(String keyId, PlayerPrincipal admin, RequestMeta meta) {
    long owner = apiKeys.revoke(keyId, null, admin.playerId());
    audit.record(AuditEntry.of(AuditCategory.KEY, "KEY_REVOKE", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("api_key", keyId).withDetail("playerId", playerQueries.byId(owner)
            .map(PlayerSummaryDto::handle).orElse(null)));
  }

  // ------------------------------------------------------------------------------------------- helpers

  /**
   * Step 2 of §10.5. Skipped (logged) while WS3 is a Day-0 stub or when the player has no account yet; any other
   * failure propagates, so the admin can retry the (idempotent) action.
   */
  private void engineStep(PlayerRecord p, Runnable command) {
    try {
      if (!NotImplemented.run(command)) {
        log.info("Account step for '{}' skipped: the trading core is not implemented in this build", p.handle());
      }
    } catch (ApiException e) {
      if (e.code() == ErrorCode.ACCOUNT_NOT_FOUND || e.code() == ErrorCode.NOT_FOUND) {
        log.info("Account step for '{}' skipped: no active account", p.handle());
        return;
      }
      throw e;
    }
  }

  private static void selfGuard(PlayerRecord target, PlayerPrincipal admin, String verb) {
    if (target.id() == admin.playerId()) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "You cannot " + verb + " yourself; ask another admin.");
    }
  }

}
