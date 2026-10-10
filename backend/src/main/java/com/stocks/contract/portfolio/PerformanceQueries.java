package com.stocks.contract.portfolio;

import com.stocks.common.domain.HistoryRange;
import com.stocks.common.error.ApiException;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PublicHistoryDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import java.util.Map;

/** Performance, history and public profile queries (§16, WS4). Visibility rules of §16.7 are applied here. */
public interface PerformanceQueries {

  PortfolioHistoryDto history(long accountId, HistoryRange range);

  PlayerProfileDto profile(String handle, Long viewerPlayerId);

  PublicHistoryDto publicHistory(String handle, HistoryRange range, Long viewerPlayerId);

  /** Delayed by {@code social.tradeFeedDelay}. */
  Page<PublicTradeDto> publicTrades(String handle, Cursor cursor, int limit);

  /** §16.2 day/total, same anchors as the board. */
  AccountPnl accountPnl(long accountId);

  /** Keyed by current symbol; maps NAME_CHANGE {@code symbol_before → symbol_after} (§16.4). */
  Map<String, PositionDayPnl> positionDayPnl(long accountId);

  /**
   * The league feed ({@code GET /feed}, MCP {@code get_league_feed}, the {@code /topic/feed} publisher): only items
   * with {@code visible_at ≤ now}, newest first (§18.2). Default: not implemented until WS4 overrides it (added by
   * WS5 so the MCP tool and the STOMP topic have a contract to call; §20.3, §19).
   */
  default Page<FeedItemDto> feed(Cursor cursor, int limit) {
    throw ApiException.notImplemented("PerformanceQueries.feed (WS4)");
  }
}
