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
 *   <li>locked ({@link LoginThrottle}: this handle from this client IP, or the handle from everywhere) → 429
 *       {@code LOGIN_LOCKED} with {@code retryAfterS}, before the password is looked at;</li>
 *   <li>unknown handle, AI player (no password) or wrong password → 401 {@code BAD_CREDENTIALS} (same bcrypt time
 *       either way), counted by {@link LoginThrottle} the same way for real and unknown handles; the failure that
 *       locks answers {@code LOGIN_LOCKED} itself;</li>
 *   <li>correct password but DISABLED → 403 {@code PLAYER_DISABLED} (a FROZEN human may still log in and look);</li>
 *   <li>success clears the (handle, IP) counter and returns the session principal (all four scopes).</li>
 * </ol>
 * Every outcome writes an {@code AUTH} audit row. The per-IP limit (10/min, §17.5) is {@code security.RateLimitFilter}.
 * {@link #reauthenticate} and the password change ({@link #verifySessionPassword}) come from an authenticated session:
 * they are <b>never</b> subject to the anonymous lockout (an attacker who knows a handle must not be able to stop its
 * owner from re-authenticating); {@code security.SessionPasswordGuard} ends a session after 5 wrong passwords instead.
 */
@Service
public class LoginService {

  private final PlayerService players;
  private final AuditService audit;
  private final LoginThrottle throttle;
  private final TimeSource time;

  public LoginService(PlayerService players, AuditService audit, LoginThrottle throttle, TimeSource time) {
    this.players = players;
    this.audit = audit;
    this.throttle = throttle;
    this.time = time;
  }

  public PlayerPrincipal authenticate(String handle, String password, RequestMeta meta) {
    String h = PlayerService.normalizeHandle(handle);
    String ip = meta == null ? null : meta.ip();
    Optional<Instant> locked = throttle.lockedUntil(h, ip);
    if (locked.isPresent()) {
      players.burnPasswordCheck(password);
      audit.record(fail("LOGIN_FAIL", meta, h, ErrorCode.LOGIN_LOCKED, AuditOutcome.RATE_LIMITED));
      throw locked(locked.get());
    }
    Optional<PlayerRecord> found = h != null && PlayerService.HANDLE.matcher(h).matches()
        ? players.byHandle(h) : Optional.empty();
    boolean known = found.isPresent() && found.get().kind() == PlayerKind.HUMAN && found.get().passwordHash() != null;
    boolean ok = known ? players.passwordMatches(found.get(), password) : burn(password);
    if (!ok) {
      Optional<Instant> lockedNow = throttle.failure(h, ip);
      ErrorCode code = lockedNow.isPresent() ? ErrorCode.LOGIN_LOCKED : ErrorCode.BAD_CREDENTIALS;
      AuditEntry entry = fail("LOGIN_FAIL", meta, h, code, AuditOutcome.DENIED);
      audit.record(known ? entry.withActor(found.get().id(), found.get().kind()) : entry);
      throw lockedNow.map(this::locked).orElseGet(LoginService::badCredentials);
    }
    PlayerRecord p = found.get();
    if (p.status() == PlayerStatus.DISABLED) {
      audit.record(fail("LOGIN_FAIL", meta, h, ErrorCode.PLAYER_DISABLED, AuditOutcome.DENIED)
          .withActor(p.id(), p.kind()));
      throw ApiException.of(ErrorCode.PLAYER_DISABLED, "This player is disabled.");
    }
    throttle.success(h, ip);
    players.recordLoginSuccess(p.id());
    PlayerPrincipal principal = principal(p);
    audit.record(AuditEntry.of(AuditCategory.AUTH, "LOGIN", AuditOutcome.OK).withActor(principal).withMeta(meta)
        .withTarget("player", p.publicId()));
    return principal;
  }

  private boolean burn(String password) {
    players.burnPasswordCheck(password);
    return false;
  }

  /**
   * {@code POST /auth/reauth}: checks the session owner's password again and returns the principal with a fresh
   * {@code authenticatedAt} (the role is re-read, so a demoted admin is not re-promoted by an old session).
   *
   * @throws ApiException 401 {@code BAD_CREDENTIALS}, 403 {@code PLAYER_DISABLED} (never {@code LOGIN_LOCKED})
   */
  public PlayerPrincipal reauthenticate(PlayerPrincipal current, String password, RequestMeta meta) {
    PlayerRecord p = players.byId(current.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "This player no longer exists."));
    if (p.kind() != PlayerKind.HUMAN || p.passwordHash() == null) {
      players.burnPasswordCheck(password);
      throw badCredentials();
    }
    verifySessionPassword(p, password, "REAUTH_FAIL", meta);
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
   * Verifies the password of the human behind an authenticated session (re-authentication, password change). Not
   * subject to the anonymous login lockout and not counted by it; the session's own failures are bounded by
   * {@code security.SessionPasswordGuard}.
   *
   * @throws ApiException 401 {@code BAD_CREDENTIALS}
   */
  public void verifySessionPassword(PlayerRecord p, String password, String failAction, RequestMeta meta) {
    if (!players.passwordMatches(p, password)) {
      audit.record(fail(failAction, meta, p.handle(), ErrorCode.BAD_CREDENTIALS, AuditOutcome.DENIED)
          .withActor(p.id(), p.kind()));
      throw badCredentials();
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
