package com.stocks.identity;

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
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Human password login (§17.3), in this order:
 * <ol>
 *   <li>unknown handle, AI player (no password) or wrong password → 401 {@code BAD_CREDENTIALS} (same bcrypt time
 *       either way);</li>
 *   <li>locked ({@code locked_until} in the future) → 429 {@code LOGIN_LOCKED} with {@code retryAfterS}; the
 *       {@code login-max-failures}-th consecutive failure (10) locks for {@code login-lockout} (15 min) and answers
 *       {@code LOGIN_LOCKED} itself;</li>
 *   <li>correct password but DISABLED → 403 {@code PLAYER_DISABLED} (a FROZEN human may still log in and look);</li>
 *   <li>success resets the counter and returns the session principal (all four scopes).</li>
 * </ol>
 * Every outcome writes an {@code AUTH} audit row. The per-IP limit (10/min, §17.5) is {@code security.RateLimitFilter}.
 * {@link #reauthenticate} backs {@code POST /auth/reauth} ({@code @RecentAuth}) with the same lockout bookkeeping.
 */
@Service
public class LoginService {

  private final PlayerService players;
  private final AuditService audit;
  private final StocksProperties properties;
  private final TimeSource time;

  public LoginService(PlayerService players, AuditService audit, StocksProperties properties, TimeSource time) {
    this.players = players;
    this.audit = audit;
    this.properties = properties;
    this.time = time;
  }

  public PlayerPrincipal authenticate(String handle, String password, RequestMeta meta) {
    String h = PlayerService.normalizeHandle(handle);
    Optional<PlayerRecord> found = h != null && PlayerService.HANDLE.matcher(h).matches()
        ? players.byHandle(h) : Optional.empty();
    if (found.isEmpty() || found.get().kind() != PlayerKind.HUMAN || found.get().passwordHash() == null) {
      players.burnPasswordCheck(password);
      audit.record(fail("LOGIN_FAIL", meta, h, ErrorCode.BAD_CREDENTIALS, AuditOutcome.DENIED));
      throw badCredentials();
    }
    PlayerRecord p = found.get();
    checkPassword(p, password, "LOGIN_FAIL", meta);
    if (p.status() == PlayerStatus.DISABLED) {
      audit.record(fail("LOGIN_FAIL", meta, h, ErrorCode.PLAYER_DISABLED, AuditOutcome.DENIED)
          .withActor(p.id(), p.kind()));
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
    }
    players.recordLoginSuccess(p.id());
    PlayerPrincipal principal = principal(p);
    audit.record(AuditEntry.of(AuditCategory.AUTH, "LOGIN", AuditOutcome.OK).withActor(principal).withMeta(meta)
        .withTarget("player", p.publicId()));
    return principal;
  }

  /**
   * {@code POST /auth/reauth}: checks the session owner's password again and returns the principal with a fresh
   * {@code authenticatedAt} (the role is re-read, so a demoted admin is not re-promoted by an old session).
   *
   * @throws ApiException 401 {@code BAD_CREDENTIALS}, 429 {@code LOGIN_LOCKED}, 403 {@code PLAYER_DISABLED}
   */
  public PlayerPrincipal reauthenticate(PlayerPrincipal current, String password, RequestMeta meta) {
    PlayerRecord p = players.byId(current.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "This player no longer exists."));
    if (p.kind() != PlayerKind.HUMAN || p.passwordHash() == null) {
      players.burnPasswordCheck(password);
      throw badCredentials();
    }
    checkPassword(p, password, "REAUTH_FAIL", meta);
    if (p.status() == PlayerStatus.DISABLED) {
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
    }
    players.recordLoginSuccess(p.id());
    PlayerPrincipal principal = principal(p);
    audit.record(AuditEntry.of(AuditCategory.AUTH, "REAUTH", AuditOutcome.OK).withActor(principal).withMeta(meta)
        .withTarget("player", p.publicId()));
    return principal;
  }

  /**
   * Verifies the password of a human, counting failures toward the §17.3 lockout.
   *
   * @throws ApiException 401 {@code BAD_CREDENTIALS} or 429 {@code LOGIN_LOCKED}
   */
  public void checkPassword(PlayerRecord p, String password, String failAction, RequestMeta meta) {
    if (players.isLocked(p)) {
      audit.record(fail(failAction, meta, p.handle(), ErrorCode.LOGIN_LOCKED, AuditOutcome.RATE_LIMITED)
          .withActor(p.id(), p.kind()));
      throw locked(p.lockedUntil());
    }
    if (!players.passwordMatches(p, password)) {
      Optional<Instant> lockedUntil = players.recordLoginFailure(p.id(), properties.security().loginMaxFailures(),
          properties.security().loginLockout());
      ErrorCode code = lockedUntil.isPresent() ? ErrorCode.LOGIN_LOCKED : ErrorCode.BAD_CREDENTIALS;
      audit.record(fail(failAction, meta, p.handle(), code, AuditOutcome.DENIED).withActor(p.id(), p.kind()));
      throw lockedUntil.map(this::locked).orElseGet(LoginService::badCredentials);
    }
  }

  /** The session principal of a human (all four scopes, {@code authenticatedAt} = now). */
  public PlayerPrincipal principal(PlayerRecord p) {
    return new PlayerPrincipal(p.id(), p.publicId(), p.handle(), p.kind(), p.role(), Scopes.ALL, AuthMethod.SESSION,
        null, null, time.now());
  }

  private static AuditEntry fail(String action, RequestMeta meta, String handle, ErrorCode code,
                                 AuditOutcome outcome) {
    return AuditEntry.of(AuditCategory.AUTH, action, outcome).withMeta(meta).withTarget("handle", handle)
        .withDetail("code", code.name());
  }

  private static ApiException badCredentials() {
    return ApiException.of(ErrorCode.BAD_CREDENTIALS, "Wrong handle or password.");
  }

  private ApiException locked(Instant until) {
    long seconds = Math.max(1, Duration.between(time.now(), until).toSeconds());
    return ApiException.of(ErrorCode.LOGIN_LOCKED, "Too many failed logins; try again later.")
        .withRetryAfter(seconds);
  }
}
