package com.stocks.identity;

import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.identity.MeQueries;
import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.identity.dto.PreferencesDto;
import com.stocks.contract.security.PlayerPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link MeQueries} (§5.3): builds §18.3 {@code Me} for {@code GET /me}, login, (later) signup and MCP
 * {@code whoami}. The player fields come from {@code player}; {@code accountId}/{@code runNo} from the player's ACTIVE
 * {@code account} row (a read-only lookup of WS3's table; null and 0 until WS3 opens the account); {@code authMethod}
 * and {@code scopes} from the caller's principal (a session holds all four scopes, a key its own).
 */
@Service
public class MeService implements MeQueries {

  private static final Logger log = LoggerFactory.getLogger(MeService.class);

  /** §18.3 defaults, also used for any key missing from the stored JSON. */
  public static final PreferencesDto DEFAULT_PREFERENCES = new PreferencesDto(PreferencesDto.Theme.DARK,
      PreferencesDto.UpDown.GREEN_UP, true, PreferencesDto.DefaultOrderType.LIMIT, TimeInForce.DAY,
      PreferencesDto.QtyMode.SHARES);

  private final PlayerService players;
  private final JdbcClient jdbc;
  private final JsonMapper jsonMapper;

  public MeService(PlayerService players, JdbcClient jdbc, JsonMapper jsonMapper) {
    this.players = players;
    this.jdbc = jdbc;
    this.jsonMapper = jsonMapper;
  }

  record ActiveAccount(String publicId, int runNo) {}

  @Override
  public MeDto me(PlayerPrincipal principal) {
    PlayerRecord p = players.byId(principal.playerId())
        .orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED, "This player no longer exists."));
    Optional<ActiveAccount> account = jdbc.sql(
            "SELECT public_id, run_no FROM account WHERE player_id = ? AND status = 'ACTIVE'")
        .param(p.id())
        .query((rs, n) -> new ActiveAccount(rs.getString("public_id"), rs.getInt("run_no")))
        .optional();
    List<String> scopes = principal.scopes().stream().sorted().toList();
    return new MeDto(p.publicId(), p.handle(), p.displayName(), p.kind(), p.aiModel(), p.role(), p.status(),
        account.map(ActiveAccount::publicId).orElse(null), account.map(ActiveAccount::runNo).orElse(0),
        preferences(p.preferences()), principal.authMethod(), scopes, p.createdAt());
  }

  /** {@code player.preferences} JSON merged over {@link #DEFAULT_PREFERENCES}; bad or unknown values fall back. */
  PreferencesDto preferences(String json) {
    if (json == null || json.isBlank()) {
      return DEFAULT_PREFERENCES;
    }
    Map<?, ?> stored;
    try {
      stored = jsonMapper.readValue(json, Map.class);
    } catch (JacksonException e) {
      log.warn("Unreadable player.preferences, using defaults: {}", e.getOriginalMessage());
      return DEFAULT_PREFERENCES;
    }
    PreferencesDto d = DEFAULT_PREFERENCES;
    return new PreferencesDto(
        enumOr(stored.get("theme"), PreferencesDto.Theme.class, d.theme()),
        enumOr(stored.get("upDown"), PreferencesDto.UpDown.class, d.upDown()),
        stored.get("confirmOrders") instanceof Boolean b ? b : d.confirmOrders(),
        enumOr(stored.get("defaultOrderType"), PreferencesDto.DefaultOrderType.class, d.defaultOrderType()),
        enumOr(stored.get("defaultTimeInForce"), TimeInForce.class, d.defaultTimeInForce()),
        enumOr(stored.get("defaultQtyMode"), PreferencesDto.QtyMode.class, d.defaultQtyMode()));
  }

  private static <E extends Enum<E>> E enumOr(Object value, Class<E> type, E fallback) {
    if (value instanceof String s) {
      for (E e : type.getEnumConstants()) {
        if (e.name().equalsIgnoreCase(s)) {
          return e;
        }
      }
    }
    return fallback;
  }
}
