package com.stocks.identity;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.error.ApiException;
import com.stocks.contract.identity.dto.SignupRequest;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invite-only signup (§17.3, §18.1 {@code POST /auth/signup}). In order:
 * <ol>
 *   <li>the invite must be usable (422 {@code INVITE_INVALID}), so nobody without an invite can probe handles;</li>
 *   <li>handle {@code ^[a-z0-9_]{2,32}$} (lower-cased), display name and password ≥ 10 characters
 *       (400 {@code VALIDATION_FAILED}, 422 {@code WEAK_PASSWORD});</li>
 *   <li>one transaction: redeem one invite slot (atomic) and insert the HUMAN player (409 {@code HANDLE_TAKEN} rolls
 *       the slot back);</li>
 *   <li>after commit, the CASH account with {@code league.defaultStartingCash} (WS3 {@code AccountService}) and the
 *       "My List" watchlist (WS4 {@code WatchlistService}) through {@link AccountProvisioner}. If the engine refuses
 *       right now the player still exists and {@link AccountBackfillRunner} opens the account on the next boot.</li>
 * </ol>
 * The per-IP limit (5/hour) is {@code security.RateLimitFilter}. Writes {@code AUTH/SIGNUP}.
 */
@Service
public class SignupService {

  private static final Logger log = LoggerFactory.getLogger(SignupService.class);

  private final InviteService invites;
  private final PlayerService players;
  private final AccountProvisioner accounts;
  private final LoginService logins;
  private final AuditService audit;
  private final TransactionTemplate tx;

  public SignupService(InviteService invites, PlayerService players, AccountProvisioner accounts, LoginService logins,
                       AuditService audit, TransactionTemplate tx) {
    this.invites = invites;
    this.players = players;
    this.accounts = accounts;
    this.logins = logins;
    this.audit = audit;
    this.tx = tx;
  }

  /** Creates the player and returns the session principal to log them in with. */
  public PlayerPrincipal signup(SignupRequest req, RequestMeta meta) {
    InviteService.Invite invite;
    try {
      invite = invites.check(req.inviteCode());
    } catch (ApiException e) {
      audit.record(AuditEntry.of(AuditCategory.AUTH, "SIGNUP", AuditOutcome.REJECTED).withMeta(meta)
          .withTarget("handle", PlayerService.normalizeHandle(req.handle())).withDetail("code", e.code().name()));
      throw e;
    }
    String handle = PlayerService.normalizeHandle(req.handle());
    PlayerService.validateHandle(handle);
    String displayName = req.displayName() == null ? null : req.displayName().trim();
    PlayerService.validateDisplayName(displayName);
    PlayerService.validatePassword(req.password());

    PlayerRecord player = tx.execute(status -> {
      invites.redeem(invite);
      return players.createHuman(handle, displayName, req.password(), PlayerRole.PLAYER);
    });

    AccountProvisioner.Outcome outcome;
    try {
      outcome = accounts.ensureAccount(player.id(), player.id());
    } catch (RuntimeException e) {
      log.warn("Signup of '{}': the first account could not be opened now ({}); it opens on the next boot",
          handle, e.toString());
      outcome = null;
    }
    PlayerPrincipal principal = logins.principal(player);
    audit.record(AuditEntry.of(AuditCategory.AUTH, "SIGNUP", AuditOutcome.OK).withActor(principal).withMeta(meta)
        .withTarget("player", player.publicId()).withDetail("invite", invite.publicId())
        .withDetail("account", outcome == null ? "FAILED" : outcome.name()));
    return principal;
  }
}
