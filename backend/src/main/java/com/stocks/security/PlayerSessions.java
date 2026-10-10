package com.stocks.security;

import com.stocks.common.events.SessionsRevoked;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes a player's browser sessions from Spring Session JDBC (§10.5 freeze, disable and role change; §17.3 password
 * change). Sessions are found by {@code PRINCIPAL_NAME}, which is the player's public id
 * ({@link PlayerAuthenticationToken#getName()}). The attribute rows go with them ({@code ON DELETE CASCADE}).
 *
 * <p>Each deletion also publishes {@link SessionsRevoked}, so realtime closes the WebSockets those sessions opened
 * (an ended session must not keep receiving the player's order, account and notification pushes).
 *
 * <p>Plain SQL rather than {@code FindByIndexNameSessionRepository}: the session of the current request still has its
 * pre-rotation id in the table until the request ends, so "every session but this one" is expressed by that stored id.
 */
@Component
public class PlayerSessions {

  private final JdbcClient jdbc;
  private final ApplicationEventPublisher events;

  public PlayerSessions(JdbcClient jdbc, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.events = events;
  }

  /** Deletes every session of the player and closes their sockets; returns how many sessions were deleted. */
  public int deleteAll(String playerPublicId) {
    int n = jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(playerPublicId).update();
    events.publishEvent(SessionsRevoked.all(playerPublicId));
    return n;
  }

  /**
   * Deletes every session of the player except the one whose stored {@code SESSION_ID} is {@code keepSessionId}
   * (the id as it was when the request arrived, i.e. before any {@code changeSessionId()}).
   */
  public int deleteOthers(String playerPublicId, String keepSessionId) {
    if (keepSessionId == null) {
      return deleteAll(playerPublicId);
    }
    int n = jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ? AND SESSION_ID <> ?")
        .params(playerPublicId, keepSessionId).update();
    events.publishEvent(SessionsRevoked.allBut(playerPublicId, keepSessionId));
    return n;
  }

  /** Number of stored sessions of the player (tests, admin detail). */
  public int count(String playerPublicId) {
    return jdbc.sql("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(playerPublicId)
        .query(Integer.class).single();
  }
}
