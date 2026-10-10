package com.stocks.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@link PerformanceQueries} (§5.3, WS4): account P&amp;L and history for the account page and WS3's {@code Account}
 * DTO; public profiles, histories and delayed trades for the league, with the §16.7 visibility rules applied here
 * (and in the leaderboard rows) and nowhere else.
 */
@Service
public class PerformanceQueriesImpl implements PerformanceQueries {

  private final AccountPerformance accounts;
  private final PortfolioHistoryService history;
  private final PublicProfileService profiles;
  private final LeagueSettings settings;

  public PerformanceQueriesImpl(AccountPerformance accounts, PortfolioHistoryService history,
                                PublicProfileService profiles, LeagueSettings settings) {
    this.accounts = accounts;
    this.history = history;
    this.profiles = profiles;
    this.settings = settings;
  }

  @Override
  public PortfolioHistoryDto history(long accountId, HistoryRange range) {
    return history.history(accountId, range);
  }

  @Override
  public PlayerProfileDto profile(String handle, Long viewerPlayerId) {
    return profiles.profile(handle, viewerPlayerId);
  }

  @Override
  public PublicHistoryDto publicHistory(String handle, HistoryRange range, Long viewerPlayerId) {
    return history.publicHistory(profiles.player(handle), range,
        settings.holdingsVisibility() == HoldingsVisibility.HIDDEN);
  }

  @Override
  public Page<PublicTradeDto> publicTrades(String handle, Cursor cursor, int limit) {
    return profiles.publicTrades(handle, cursor, limit);
  }

  @Override
  public AccountPnl accountPnl(long accountId) {
    return accounts.accountPnl(accountId);
  }

  @Override
  public Map<String, PositionDayPnl> positionDayPnl(long accountId) {
    return accounts.positionDayPnl(accountId);
  }
}
