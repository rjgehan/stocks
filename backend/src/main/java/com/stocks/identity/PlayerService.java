package com.stocks.identity;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerRole;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Players: creation with the §8.1/§17 validation rules, lookups, and the login bookkeeping behind §17.3 lockout.
 *
 * <ul>
 *   <li>Handle {@code ^[a-z0-9_]{2,32}$} (the human login name; input is lower-cased), display name
 *       {@code ^[A-Za-z0-9 _.\-]{2,40}$} (§17.8) → 400 {@code VALIDATION_FAILED}; a taken handle → 409
 *       {@code HANDLE_TAKEN}.</li>
 *   <li>Passwords: at least 10 characters (§17.3) and at most 72 UTF-8 bytes (bcrypt's limit) → 422
 *       {@code WEAK_PASSWORD}. Hashed with the {@code DelegatingPasswordEncoder} (bcrypt cost 12).</li>
 *   <li>Only a HUMAN can be ADMIN and only humans have a password (DB CHECKs, §17.4); AI players need a human owner.</li>
 * </ul>
 * Phase 0 covers what login, the bootstrap admin and the dev seed need. TODO(WS1, Phase 1): signup, PATCH /me,
 * password change (revokes personal keys, deletes other sessions), admin create/update/freeze, PlayerQueries.
 */
@Service
public class PlayerService {

  public static final Pattern HANDLE = Pattern.compile("^[a-z0-9_]{2,32}$");
  public static final Pattern DISPLAY_NAME = Pattern.compile("^[A-Za-z0-9 _.\\-]{2,40}$");
  public static final int MIN_PASSWORD_CHARS = 10;
  public static final int MAX_PASSWORD_BYTES = 72;

  private static final String COLUMNS = """
      id, public_id, handle, display_name, kind, role, status, ai_model, owner_player_id, password_hash,
      failed_logins, locked_until, avatar_seed, preferences, api_trading_allowed, created_at""";

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final PublicId publicId;
  private final PasswordEncoder passwordEncoder;

  public PlayerService(JdbcClient jdbc, TimeSource time, PublicId publicId, PasswordEncoder passwordEncoder) {
    this.jdbc = jdbc;
    this.time = time;
    this.publicId = publicId;
    this.passwordEncoder = passwordEncoder;
  }

  // ------------------------------------------------------------------------------------------- validation

  /** Lower-cased, trimmed handle (null stays null). */
  public static String normalizeHandle(String handle) {
    return handle == null ? null : handle.trim().toLowerCase(Locale.ROOT);
  }

  public static void validateHandle(String handle) {
    if (handle == null || !HANDLE.matcher(handle).matches()) {
      throw ApiException.validation("handle", "2-32 characters: lowercase letters, digits and _");
    }
  }

  public static void validateDisplayName(String displayName) {
    if (displayName == null || !DISPLAY_NAME.matcher(displayName).matches()) {
      throw ApiException.validation("displayName", "2-40 characters: letters, digits, space, _ . -");
    }
  }

  /** §17.3: at least 10 characters; bcrypt reads at most 72 bytes, so longer input is refused, not truncated. */
  public static void validatePassword(String password) {
    if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARS) {
      throw ApiException.of(ErrorCode.WEAK_PASSWORD, "Passwords need at least " + MIN_PASSWORD_CHARS + " characters.")
          .withField("password", ErrorCode.WEAK_PASSWORD, "At least " + MIN_PASSWORD_CHARS + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
      throw ApiException.of(ErrorCode.WEAK_PASSWORD, "Passwords may be at most " + MAX_PASSWORD_BYTES + " bytes.")
          .withField("password", ErrorCode.WEAK_PASSWORD, "At most " + MAX_PASSWORD_BYTES + " bytes");
    }
  }

  // ------------------------------------------------------------------------------------------- creation

  /** A HUMAN player (role PLAYER or ADMIN). Does not open an account (see {@link AccountProvisioner}). */
  public PlayerRecord createHuman(String handle, String displayName, String password, PlayerRole role) {
    String h = normalizeHandle(handle);
    validateHandle(h);
    validateDisplayName(displayName);
    validatePassword(password);
    return insert(h, displayName, PlayerKind.HUMAN, role, null, null, passwordEncoder.encode(password));
  }

  /** An AI player owned by a human (§8.1 {@code ck_player_ai_has_owner}); never ADMIN, never a password. */
  public PlayerRecord createAi(String handle, String displayName, String aiModel, long ownerPlayerId) {
    String h = normalizeHandle(handle);
    validateHandle(h);
    validateDisplayName(displayName);
    if (aiModel == null || aiModel.isBlank() || aiModel.length() > 24) {
      throw ApiException.validation("aiModel", "1-24 characters, e.g. Claude or ChatGPT");
    }
    return insert(h, displayName, PlayerKind.AI, PlayerRole.PLAYER, aiModel.trim(), ownerPlayerId, null);
  }

  private PlayerRecord insert(String handle, String displayName, PlayerKind kind, PlayerRole role, String aiModel,
                              Long ownerPlayerId, String passwordHash) {
    String id = publicId.next(Prefix.PLAYER);
    try {
      jdbc.sql("""
              INSERT INTO player (public_id, handle, display_name, kind, role, status, ai_model, owner_player_id,
                                  password_hash, avatar_seed, created_at)
              VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)""")
          .params(id, handle, displayName, kind.name(), role.name(), aiModel, ownerPlayerId, passwordHash, handle,
              Jdbc.ts(time.now()))
          .update();
    } catch (DuplicateKeyException e) {
      throw ApiException.of(ErrorCode.HANDLE_TAKEN, "The handle '" + handle + "' is taken.")
          .withField("handle", ErrorCode.HANDLE_TAKEN, "Taken");
    }
    return byPublicId(id).orElseThrow();
  }

  // ------------------------------------------------------------------------------------------- lookups

  public Optional<PlayerRecord> byHandle(String handle) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE handle = ?").param(normalizeHandle(handle))
        .query(PlayerService::map).optional();
  }

  public Optional<PlayerRecord> byId(long id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE id = ?").param(id)
        .query(PlayerService::map).optional();
  }

  public Optional<PlayerRecord> byPublicId(String playerPublicId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM player WHERE public_id = ?").param(playerPublicId)
        .query(PlayerService::map).optional();
  }

  /** True if at least one ADMIN exists (whatever its status). */
  public boolean anyAdmin() {
    return jdbc.sql("SELECT COUNT(*) FROM player WHERE role = 'ADMIN'").query(Long.class).single() > 0;
  }

  // ------------------------------------------------------------------------------------------- login bookkeeping

  /** Verifies a human's password; false for AI players (no password) and unknown hashes. */
  public boolean passwordMatches(PlayerRecord player, String rawPassword) {
    if (player.passwordHash() == null || rawPassword == null) {
      return false;
    }
    return passwordEncoder.matches(rawPassword, player.passwordHash());
  }

  /** Spends the same bcrypt time as a real check, so unknown handles and AI players are not faster (timing). */
  public void burnPasswordCheck(String rawPassword) {
    String hash = dummyHash;
    if (hash == null) {
      hash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
      dummyHash = hash;
    }
    passwordEncoder.matches(rawPassword == null ? "" : rawPassword, hash);
  }

  private volatile String dummyHash;

  public void recordLoginSuccess(long playerId) {
    jdbc.sql("UPDATE player SET failed_logins = 0, locked_until = NULL, last_seen_at = ? WHERE id = ?")
        .params(Jdbc.ts(time.now()), playerId).update();
  }

  /**
   * Counts a failed password. On the {@code maxFailures}-th consecutive failure the player is locked for
   * {@code lockout} and the counter restarts (§17.3: 10 failures → 15 minutes).
   *
   * @return the lock's end if this failure locked the player, else empty
   */
  public Optional<Instant> recordLoginFailure(long playerId, int maxFailures, Duration lockout) {
    jdbc.sql("UPDATE player SET failed_logins = failed_logins + 1 WHERE id = ?").param(playerId).update();
    int failures = jdbc.sql("SELECT failed_logins FROM player WHERE id = ?").param(playerId)
        .query(Integer.class).single();
    if (failures < maxFailures) {
      return Optional.empty();
    }
    Instant until = time.now().plus(lockout);
    jdbc.sql("UPDATE player SET failed_logins = 0, locked_until = ? WHERE id = ?")
        .params(Jdbc.ts(until), playerId).update();
    return Optional.of(until);
  }

  /** True while {@code locked_until} is in the future. */
  public boolean isLocked(PlayerRecord player) {
    return player.lockedUntil() != null && player.lockedUntil().isAfter(time.now());
  }

  static PlayerRecord map(ResultSet rs, int rowNum) throws SQLException {
    return new PlayerRecord(rs.getLong("id"), rs.getString("public_id"), rs.getString("handle"),
        rs.getString("display_name"), PlayerKind.valueOf(rs.getString("kind")),
        PlayerRole.valueOf(rs.getString("role")), PlayerStatus.valueOf(rs.getString("status")),
        rs.getString("ai_model"), Jdbc.longOrNull(rs, "owner_player_id"), rs.getString("password_hash"),
        rs.getInt("failed_logins"), Jdbc.instant(rs, "locked_until"), rs.getString("avatar_seed"),
        rs.getString("preferences"), rs.getBoolean("api_trading_allowed"), Jdbc.instant(rs, "created_at"));
  }
}
