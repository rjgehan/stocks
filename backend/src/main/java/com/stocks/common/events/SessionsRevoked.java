package com.stocks.common.events;

/**
 * Browser sessions ended on the server (§17.3 password change and logout; §10.5 freeze, disable, role change and
 * admin password reset). Published synchronously by WS1's {@code PlayerSessions} and {@code SessionLogin}; realtime
 * closes the WebSockets those sessions opened, so an ended session stops receiving the player's private pushes.
 *
 * @param playerPublicId    the player whose sessions ended (null only for a logout without a principal)
 * @param sessionIdOrNull   only this session ended (logout); null = every session of the player
 * @param keepSessionIdOrNull every session of the player except this one ended (password change); null = none kept
 */
public record SessionsRevoked(String playerPublicId, String sessionIdOrNull, String keepSessionIdOrNull) {

  /** Every session of the player. */
  public static SessionsRevoked all(String playerPublicId) {
    return new SessionsRevoked(playerPublicId, null, null);
  }

  /** Every session of the player but {@code keepSessionId} (the caller's own). */
  public static SessionsRevoked allBut(String playerPublicId, String keepSessionId) {
    return new SessionsRevoked(playerPublicId, null, keepSessionId);
  }

  /** One session (logout). */
  public static SessionsRevoked one(String playerPublicIdOrNull, String sessionId) {
    return new SessionsRevoked(playerPublicIdOrNull, sessionId, null);
  }

  /** Whether a socket of {@code socketPlayerPublicId}, opened by HTTP session {@code httpSessionId}, must close. */
  public boolean covers(String socketPlayerPublicId, String httpSessionId) {
    if (sessionIdOrNull != null) {
      return sessionIdOrNull.equals(httpSessionId);
    }
    if (playerPublicId == null || !playerPublicId.equals(socketPlayerPublicId)) {
      return false;
    }
    return keepSessionIdOrNull == null || !keepSessionIdOrNull.equals(httpSessionId);
  }
}
