package com.stocks.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes a player's browser sessions from Spring Session JDBC (§10.5 freeze, disable and role change; §17.3 password
 * change). Sessions are found by {@code PRINCIPAL_NAME}, which is the player's public id
 * ({@link PlayerAuthenticationToken#getName()}). The attribute rows go with them ({@code ON DELETE CASCADE}).
 *
 * <p>Plain SQL rather than {@code FindByIndexNameSessionRepository}: the session of the current request still has its
 * pre-rotation id in the table until the request ends, so "every session but this one" is expressed by that stored id.
 */
@Component
public class PlayerSessions {

  private final JdbcClient jdbc;

  public PlayerSessions(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Deletes every session of the player; returns how many were deleted. */
  public int deleteAll(String playerPublicId) {
    return jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(playerPublicId).update();
  }

  /**
   * Deletes every session of the player except the one whose stored {@code SESSION_ID} is {@code keepSessionId}
   * (the id as it was when the request arrived, i.e. before any {@code changeSessionId()}).
   */
  public int deleteOthers(String playerPublicId, String keepSessionId) {
    if (keepSessionId == null) {
      return deleteAll(playerPublicId);
    }
    return jdbc.sql("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ? AND SESSION_ID <> ?")
        .params(playerPublicId, keepSessionId).update();
  }

  /** Number of stored sessions of the player (tests, admin detail). */
  public int count(String playerPublicId) {
    return jdbc.sql("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?").param(playerPublicId)
        .query(Integer.class).single();
  }
}
