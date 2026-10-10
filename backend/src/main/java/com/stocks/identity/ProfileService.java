package com.stocks.identity;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.dto.PreferencesDto;
import com.stocks.contract.identity.dto.UpdateMeRequest;
import com.stocks.contract.identity.dto.UpdatePreferencesRequest;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The player's own profile (§18.1): {@code PATCH /me} (display name, preferences merged over the stored ones) and
 * {@code POST /me/password} (§17.3: verify the current password with the login lockout bookkeeping, set the new one,
 * revoke the player's personal API keys). Deleting the player's other sessions and rotating the session id are the
 * controller's part (they need the request).
 */
@Service
public class ProfileService {

  private final PlayerService players;
  private final MeService me;
  private final LoginService logins;
  private final ApiKeyService apiKeys;
  private final AuditService audit;
  private final JsonMapper jsonMapper;
  private final TransactionTemplate tx;

  public ProfileService(PlayerService players, MeService me, LoginService logins, ApiKeyService apiKeys,
                        AuditService audit, JsonMapper jsonMapper, TransactionTemplate tx) {
    this.players = players;
    this.me = me;
    this.logins = logins;
    this.apiKeys = apiKeys;
    this.audit = audit;
    this.jsonMapper = jsonMapper;
    this.tx = tx;
  }

  /** Applies the non-null fields of {@code req}. */
  public void update(PlayerPrincipal who, UpdateMeRequest req) {
    PlayerRecord p = require(who);
    if (req == null) {
      return;
    }
    String displayName = req.displayName() == null ? null : req.displayName().trim();
    if (displayName != null) {
      PlayerService.validateDisplayName(displayName);
    }
    String preferencesJson = req.preferences() == null ? null : merge(me.preferences(p.preferences()),
        req.preferences());
    tx.executeWithoutResult(status -> {
      if (displayName != null) {
        players.updateDisplayName(p.id(), displayName);
      }
      if (preferencesJson != null) {
        players.updatePreferences(p.id(), preferencesJson);
      }
    });
  }

  /**
   * Changes the password and revokes every personal API key of the player.
   *
   * @return the number of keys revoked
   * @throws ApiException 401 {@code BAD_CREDENTIALS}, 422 {@code WEAK_PASSWORD}
   */
  public int changePassword(PlayerPrincipal who, String currentPassword, String newPassword, RequestMeta meta) {
    PlayerRecord p = require(who);
    logins.verifySessionPassword(p, currentPassword, "PASSWORD_CHANGE_FAIL", meta);
    PlayerService.validatePassword(newPassword);
    if (newPassword.equals(currentPassword)) {
      throw ApiException.of(ErrorCode.WEAK_PASSWORD, "The new password must differ from the current one.")
          .withField("newPassword", ErrorCode.WEAK_PASSWORD, "Same as the current password");
    }
    Integer revoked = tx.execute(status -> {
      players.setPassword(p, newPassword);
      return apiKeys.revokeAll(p.id(), p.id());
    });
    players.recordLoginSuccess(p.id());
    audit.record(AuditEntry.of(AuditCategory.AUTH, "PASSWORD_CHANGE", AuditOutcome.OK).withActor(who).withMeta(meta)
        .withTarget("player", p.publicId()).withDetail("keysRevoked", revoked));
    return revoked == null ? 0 : revoked;
  }

  private PlayerRecord require(PlayerPrincipal who) {
    return players.byId(who.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "This player no longer exists."));
  }

  /** {@code Partial<Preferences>} over the current preferences, as the stored JSON object. */
  String merge(PreferencesDto current, UpdatePreferencesRequest patch) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("theme", (patch.theme() != null ? patch.theme() : current.theme()).name());
    out.put("upDown", (patch.upDown() != null ? patch.upDown() : current.upDown()).name());
    out.put("confirmOrders", patch.confirmOrders() != null ? patch.confirmOrders() : current.confirmOrders());
    out.put("defaultOrderType",
        (patch.defaultOrderType() != null ? patch.defaultOrderType() : current.defaultOrderType()).name());
    out.put("defaultTimeInForce",
        (patch.defaultTimeInForce() != null ? patch.defaultTimeInForce() : current.defaultTimeInForce()).name());
    out.put("defaultQtyMode",
        (patch.defaultQtyMode() != null ? patch.defaultQtyMode() : current.defaultQtyMode()).name());
    return jsonMapper.writeValueAsString(out);
  }
}
