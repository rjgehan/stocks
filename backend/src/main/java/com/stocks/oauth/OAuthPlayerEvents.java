package com.stocks.oauth;

import com.stocks.common.domain.PlayerStatus;
import com.stocks.common.events.SessionsRevoked;
import com.stocks.contract.security.RequestMeta;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps OAuth grants in step with the player's credentials (§17.9.4). Whenever every browser session of a player is
 * ended ({@link SessionsRevoked} without a single session id: a password change, an admin password reset, a role
 * change, a freeze or a disable), the player's grants (as the consenting human or as the actor) are revoked, except
 * for a FROZEN player: a freeze is reversible and its tokens are refused with 403 {@code PLAYER_FROZEN} anyway. A
 * DISABLED player's grants are revoked as {@code PLAYER_DISABLED}, the others as {@code CREDENTIALS_CHANGED}. Runs
 * in the publisher's transaction; a failure is logged, never thrown into the caller's operation.
 */
@Component
public class OAuthPlayerEvents {

  private static final Logger log = LoggerFactory.getLogger(OAuthPlayerEvents.class);

  private final JdbcClient jdbc;
  private final OAuthGrants grants;

  public OAuthPlayerEvents(JdbcClient jdbc, OAuthGrants grants) {
    this.jdbc = jdbc;
    this.grants = grants;
  }

  @EventListener
  public void onSessionsRevoked(SessionsRevoked e) {
    if (e.sessionIdOrNull() != null || e.playerPublicId() == null) {
      return; // a single logout
    }
    try {
      Optional<Row> player = jdbc.sql("SELECT id, status FROM player WHERE public_id = ?").param(e.playerPublicId())
          .query((rs, n) -> new Row(rs.getLong("id"), PlayerStatus.valueOf(rs.getString("status")))).optional();
      if (player.isEmpty() || player.get().status() == PlayerStatus.FROZEN) {
        return;
      }
      String reason = player.get().status() == PlayerStatus.DISABLED ? OAuthGrants.PLAYER_DISABLED
          : OAuthGrants.CREDENTIALS_CHANGED;
      int n = grants.revokeAllOf(player.get().id(), reason, RequestMeta.system(null));
      if (n > 0) {
        log.info("revoked {} OAuth grant(s) of player {} ({})", n, e.playerPublicId(), reason);
      }
    } catch (RuntimeException ex) {
      log.error("Could not revoke the OAuth grants of player {}: {}", e.playerPublicId(), ex.toString());
    }
  }

  private record Row(long id, PlayerStatus status) {}
}
