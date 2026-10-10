package com.stocks.contract.identity;

import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.PlayerStatus;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import java.util.List;
import java.util.Optional;

/** Read-only player lookups (§5.3, WS1). */
public interface PlayerQueries {

  Optional<PlayerSummaryDto> byHandle(String handle);

  Optional<PlayerSummaryDto> byId(long playerId);

  /** {@code kindOrNull} null = both kinds; inactive = DISABLED players. */
  List<PlayerSummaryDto> all(PlayerKind kindOrNull, boolean includeInactive);

  /** Cached; {@code AdminPlayerService} evicts synchronously on change (§10.5). */
  PlayerStatus status(long playerId);
}
