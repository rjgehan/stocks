package com.stocks.identity.web;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.dto.ApiKeyCreatedDto;
import com.stocks.contract.identity.dto.ApiKeyInfoDto;
import com.stocks.contract.identity.dto.CreateApiKeyRequest;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.Scopes;
import com.stocks.identity.ApiKeyService;
import com.stocks.identity.PlayerRecord;
import com.stocks.identity.PlayerService;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §18.1 personal API keys of a human ({@code /settings/api-keys}): session only (chain 2 {@code /me/**}). Keys are
 * read-only by default ({@code market:read account:read league:read}); {@code trade} only when the admin allowed API
 * trading for the player (403 {@code FORBIDDEN_SCOPE} otherwise). Creating one needs a recent password check. All of
 * them are revoked on a password change (§17.2).
 */
@RestController
@RequestMapping("/api/v1/me/api-keys")
@Tag(name = "me", description = "The authenticated player")
public class MyApiKeyController {

  private final ApiKeyService apiKeys;
  private final PlayerService players;
  private final AuditService audit;

  public MyApiKeyController(ApiKeyService apiKeys, PlayerService players, AuditService audit) {
    this.apiKeys = apiKeys;
    this.players = players;
    this.audit = audit;
  }

  @GetMapping
  @Operation(summary = "My API keys", description = "Session only. Every key, newest first, revoked ones included "
      + "(revokedAt set). Never returns a secret.")
  public List<ApiKeyInfoDto> list() {
    return apiKeys.list(CurrentPlayer.require().playerId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RecentAuth
  @Operation(summary = "Create a personal API key",
      description = "Session only, recent password check. scopes default to the three read scopes; 'trade' needs "
          + "the admin's 'allow API trading'. expiresInDays 1-365 or omitted (no expiry). The token is shown once. "
          + "Errors: VALIDATION_FAILED, FORBIDDEN (AI players), FORBIDDEN_SCOPE, RECENT_AUTH_REQUIRED.")
  public ApiKeyCreatedDto create(@Valid @RequestBody CreateApiKeyRequest body, HttpServletRequest request) {
    PlayerPrincipal who = CurrentPlayer.require();
    PlayerRecord me = players.byId(who.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "This player no longer exists."));
    if (me.kind() != PlayerKind.HUMAN) {
      throw ApiException.of(ErrorCode.FORBIDDEN, "AI players get their keys from the admin.");
    }
    Set<String> scopes = ApiKeyService.parseScopes(body.scopes(), Scopes.READ_ONLY);
    if (scopes.contains(Scopes.TRADE) && !me.apiTradingAllowed()) {
      throw ApiException.of(ErrorCode.FORBIDDEN_SCOPE, "Trading through an API key needs the admin's approval.")
          .withField("scopes", ErrorCode.FORBIDDEN_SCOPE, "'trade' is not allowed for this player")
          .withHint("Ask the admin to allow API trading, or create a read-only key.");
    }
    ApiKeyService.IssuedKey key = apiKeys.issue(me.id(), body.name(), scopes, apiKeys.expiry(body.expiresInDays()),
        me.id());
    audit.record(AuditEntry.of(AuditCategory.KEY, "KEY_ISSUE", AuditOutcome.OK).withActor(who)
        .withMeta(RequestMetas.of(request, who)).withTarget("api_key", key.keyId())
        .withDetail("player", me.handle()).withDetail("scopes", new TreeSet<>(scopes)).withDetail("personal", true));
    return apiKeys.created(key);
  }

  @DeleteMapping("/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Revoke one of my API keys", description = "Session only. Immediate. Errors: NOT_FOUND.")
  public void revoke(@PathVariable String keyId, HttpServletRequest request) {
    PlayerPrincipal who = CurrentPlayer.require();
    apiKeys.revoke(keyId, who.playerId(), who.playerId());
    audit.record(AuditEntry.of(AuditCategory.KEY, "KEY_REVOKE", AuditOutcome.OK).withActor(who)
        .withMeta(RequestMetas.of(request, who)).withTarget("api_key", keyId).withDetail("personal", true));
  }
}
