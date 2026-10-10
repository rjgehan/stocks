package com.stocks.contract.trading;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.ViolationDto;
import java.time.LocalDate;
import java.util.List;

/** Read-only account queries (WS3). */
public interface AccountQueries {

  /** @throws com.stocks.common.error.ApiException {@code NOT_FOUND} if the player has no active account */
  long activeAccountId(long playerId);

  /** §18.3 Account, valued live (uses {@code PerformanceQueries}). */
  AccountDto account(long accountId);

  /** §10.2, DB only, no marks. */
  Balances balances(long accountId, LocalDate asOfEt);

  /** {@code dayPnl} from {@code PerformanceQueries.positionDayPnl}. */
  List<PositionDto> positions(long accountId);

  /** §18.2 merged ledger and account_event rows, newest first. */
  Page<ActivityDto> activities(long accountId, ActivityFilter filter, Cursor cursor, int limit);

  List<ViolationDto> violations(long accountId);

  List<RunDto> runs(long playerId);
}
