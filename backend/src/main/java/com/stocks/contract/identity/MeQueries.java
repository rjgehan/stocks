package com.stocks.contract.identity;

import com.stocks.contract.identity.dto.MeDto;
import com.stocks.contract.security.PlayerPrincipal;

/**
 * Builds §18.3 {@code Me} for the caller (§5.3, WS1): REST {@code GET /me}, login, and MCP {@code whoami}, so the
 * edges (§5.2 rule 2) reach it through the contract instead of {@code com.stocks.identity}.
 */
public interface MeQueries {

  /**
   * {@code Me} for {@code principal}: player fields from the database, {@code accountId}/{@code runNo} from the
   * ACTIVE account (null/0 without one), {@code authMethod} and {@code scopes} from the principal.
   * {@code ApiException(UNAUTHENTICATED)} if the player no longer exists.
   */
  MeDto me(PlayerPrincipal principal);
}
