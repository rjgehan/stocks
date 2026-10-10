package com.stocks.identity;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.config.StocksProperties;
import com.stocks.contract.admin.dto.InviteCreatedDto;
import com.stocks.contract.admin.dto.InviteDto;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Invite links for the invite-only signup (§17.3, §21.1). An invite has a public id ({@code inv_…}), a random
 * 20-character code (lowercase base32, 100 bits) of which only the SHA-256 is stored, a use limit (1–50) and an
 * expiry (1–720 hours). The SPA opens {@code <public-url>/join/<code>} (§22.2). Redeeming is one conditional
 * {@code UPDATE}, so two concurrent signups can never use one slot twice; it runs in the caller's transaction, so a
 * failed signup (handle taken) gives the slot back.
 */
@Service
public class InviteService {

  public static final int MAX_USES = 50;
  public static final int MAX_EXPIRES_IN_HOURS = 720;
  static final int CODE_LENGTH = 20;
  static final Pattern CODE = Pattern.compile("^[a-z2-7]{" + CODE_LENGTH + "}$");
  private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

  /** One invite as stored, without its code. */
  public record Invite(long id, String publicId, long createdBy, int maxUses, int uses, Instant createdAt,
                       Instant expiresAt, Instant revokedAt) {}

  private final JdbcClient jdbc;
  private final TimeSource time;
  private final PublicId publicId;
  private final StocksProperties properties;
  private final SecureRandom random = new SecureRandom();

  public InviteService(JdbcClient jdbc, TimeSource time, PublicId publicId, StocksProperties properties) {
    this.jdbc = jdbc;
    this.time = time;
    this.publicId = publicId;
    this.properties = properties;
  }

  /**
   * Creates an invite and returns its one-time code and link.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED} for out-of-range uses or hours
   */
  public InviteCreatedDto create(Integer maxUses, Integer expiresInHours, long createdBy) {
    if (maxUses == null || maxUses < 1 || maxUses > MAX_USES) {
      throw ApiException.validation("maxUses", "1-" + MAX_USES);
    }
    if (expiresInHours == null || expiresInHours < 1 || expiresInHours > MAX_EXPIRES_IN_HOURS) {
      throw ApiException.validation("expiresInHours", "1-" + MAX_EXPIRES_IN_HOURS);
    }
    String code = newCode();
    String id = publicId.next(Prefix.INVITE);
    Instant now = time.now();
    Instant expiresAt = now.plus(Duration.ofHours(expiresInHours));
    jdbc.sql("""
            INSERT INTO invite (public_id, code_hash, created_by, max_uses, uses, created_at, expires_at)
            VALUES (?, ?, ?, ?, 0, ?, ?)""")
        .params(id, IdempotencyKeys.sha256Hex(code), createdBy, maxUses, Jdbc.ts(now), Jdbc.ts(expiresAt))
        .update();
    return new InviteCreatedDto(id, code, url(code), Jdbc.ts(expiresAt).toInstant());
  }

  /** The signup link for a code: {@code <public-url>/join/<code>} (§22.2). */
  public String url(String code) {
    String base = properties.publicUrl() == null ? "" : properties.publicUrl().trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/join/" + code;
  }

  /** Every invite, newest first (expired, used up and revoked ones included). */
  public List<InviteDto> list() {
    return jdbc.sql("SELECT * FROM invite ORDER BY created_at DESC, id DESC")
        .query((rs, n) -> new InviteDto(rs.getString("public_id"), rs.getInt("uses"), rs.getInt("max_uses"),
            Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "revoked_at")))
        .list();
  }

  /**
   * Revokes an invite (idempotent).
   *
   * @throws ApiException 404 {@code NOT_FOUND}
   */
  public void revoke(String inviteId) {
    if (find(inviteId).isEmpty()) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "No such invite.");
    }
    jdbc.sql("UPDATE invite SET revoked_at = ? WHERE public_id = ? AND revoked_at IS NULL")
        .params(Jdbc.ts(time.now()), inviteId).update();
  }

  public Optional<Invite> find(String inviteId) {
    if (inviteId == null || inviteId.length() > 32) {
      return Optional.empty();
    }
    return jdbc.sql("SELECT * FROM invite WHERE public_id = ?").param(inviteId)
        .query((rs, n) -> new Invite(rs.getLong("id"), rs.getString("public_id"), rs.getLong("created_by"),
            rs.getInt("max_uses"), rs.getInt("uses"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "expires_at"),
            Jdbc.instant(rs, "revoked_at")))
        .optional();
  }

  /**
   * The usable invite for a code, without consuming it.
   *
   * @throws ApiException 422 {@code INVITE_INVALID} (unknown, revoked, expired or used up)
   */
  public Invite check(String code) {
    String c = normalize(code);
    Optional<Invite> invite = c == null ? Optional.empty()
        : jdbc.sql("SELECT * FROM invite WHERE code_hash = ?").param(IdempotencyKeys.sha256Hex(c))
            .query((rs, n) -> new Invite(rs.getLong("id"), rs.getString("public_id"), rs.getLong("created_by"),
                rs.getInt("max_uses"), rs.getInt("uses"), Jdbc.instant(rs, "created_at"),
                Jdbc.instant(rs, "expires_at"), Jdbc.instant(rs, "revoked_at")))
            .optional();
    Instant now = time.now();
    if (invite.isEmpty() || invite.get().revokedAt() != null || !invite.get().expiresAt().isAfter(now)
        || invite.get().uses() >= invite.get().maxUses()) {
      throw invalid();
    }
    return invite.get();
  }

  /**
   * Uses one slot of the invite, atomically, in the caller's transaction.
   *
   * @throws ApiException 422 {@code INVITE_INVALID} if it became unusable meanwhile
   */
  public void redeem(Invite invite) {
    int n = jdbc.sql("""
            UPDATE invite SET uses = uses + 1
             WHERE id = ? AND revoked_at IS NULL AND expires_at > ? AND uses < max_uses""")
        .params(invite.id(), Jdbc.ts(time.now())).update();
    if (n != 1) {
      throw invalid();
    }
  }

  /** True if any invite is still usable (dev seed). */
  public boolean anyUsable() {
    return jdbc.sql("""
            SELECT COUNT(*) FROM invite WHERE revoked_at IS NULL AND expires_at > ? AND uses < max_uses""")
        .param(Jdbc.ts(time.now())).query(Long.class).single() > 0;
  }

  static String normalize(String code) {
    if (code == null) {
      return null;
    }
    String c = code.trim().toLowerCase(Locale.ROOT);
    return CODE.matcher(c).matches() ? c : null;
  }

  private String newCode() {
    char[] out = new char[CODE_LENGTH];
    for (int i = 0; i < out.length; i++) {
      out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
    }
    return new String(out);
  }

  private static ApiException invalid() {
    return ApiException.of(ErrorCode.INVITE_INVALID, "This invite link is invalid, expired or used up.")
        .withField("inviteCode", ErrorCode.INVITE_INVALID, "Invalid, expired or used up")
        .withHint("Ask the league admin for a new invite link.");
  }
}
