package com.stocks.league;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.AccountPnl;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.PositionDayPnl;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import com.stocks.portfolio.AccountPerformance;
import com.stocks.portfolio.LeagueSettings;
import com.stocks.portfolio.PortfolioHistoryService;
import com.stocks.portfolio.PublicProfileService;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@link PerformanceQueries} (§5.3, WS4): account P&amp;L and history for the account page and WS3's {@code Account}
 * DTO; public profiles, histories, delayed trades and the feed for the league (REST and MCP), with the §16.7
 * visibility rules applied here (and in the leaderboard rows) and nowhere else. It lives in {@code league} because it
 * fronts both packages ({@code league} depends on {@code portfolio}, never the reverse, §5.2 rule 7).
 */
@Service
public class PerformanceQueriesImpl implements PerformanceQueries {

  private final AccountPerformance accounts;
  private final PortfolioHistoryService history;
  private final PublicProfileService profiles;
  private final FeedService feed;
  private final LeagueSettings settings;

  public PerformanceQueriesImpl(AccountPerformance accounts, PortfolioHistoryService history,
                                PublicProfileService profiles, FeedService feed, LeagueSettings settings) {
    this.accounts = accounts;
    this.history = history;
    this.profiles = profiles;
    this.feed = feed;
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

  @Override
  public Page<FeedItemDto> feed(Cursor cursor, int limit) {
    return feed.page(cursor, limit);
  }
}
