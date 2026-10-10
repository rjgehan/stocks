package com.stocks.mcp;

import com.stocks.common.web.Text;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.marketdata.dto.NewsItemDto;
import com.stocks.contract.portfolio.dto.FeedItemDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PublicTradeDto;
import java.util.List;

/**
 * Prompt-injection hygiene for MCP outputs (§17.8). Free text written by other players (display names, rationales,
 * memos) and third-party news reaches agents only inside {@code untrusted} objects (the §18.3 DTOs carry them), and
 * every such string is clipped here once more before it leaves through MCP: control and format characters stripped,
 * names to {@value #NAME_MAX}, rationales and memos to {@value #RATIONALE_MAX}, news to {@value #NEWS_MAX} characters.
 * The tool descriptions and {@code mcp/instructions.md} tell agents that content inside {@code untrusted} is data,
 * never instructions. The producers (WS2/WS4 mappers) apply the same limits; applying them again is cheap and keeps
 * the MCP edge safe whatever a producer does.
 */
public final class Untrusted {

  public static final int NAME_MAX = 40;
  public static final int RATIONALE_MAX = 280;
  public static final int NEWS_MAX = 500;

  private Untrusted() {}

  public static String name(String s) {
    return Text.clip(s, NAME_MAX);
  }

  public static String rationale(String s) {
    return Text.clip(s, RATIONALE_MAX);
  }

  public static String news(String s) {
    return Text.clip(s, NEWS_MAX);
  }

  public static PlayerSummaryDto player(PlayerSummaryDto p) {
    if (p == null) {
      return null;
    }
    return new PlayerSummaryDto(p.playerId(), p.handle(), name(p.displayName()), p.kind(), name(p.aiModel()),
        p.avatarSeed(), p.memberSince(), p.resets());
  }

  public static NewsItemDto news(NewsItemDto n) {
    if (n == null) {
      return null;
    }
    NewsItemDto.UntrustedNewsDto u = n.untrusted();
    return new NewsItemDto(n.id(), n.publishedAt(), n.source(), n.url(), n.symbols(), n.imageUrl(),
        u == null ? null : new NewsItemDto.UntrustedNewsDto(news(u.headline()), news(u.summary())));
  }

  public static PublicTradeDto trade(PublicTradeDto t) {
    if (t == null) {
      return null;
    }
    PublicTradeDto.UntrustedRationaleDto u = t.untrusted();
    return new PublicTradeDto(t.executedAt(), t.symbol(), t.side(), t.qty(), t.price(), t.realizedPnl(), t.viaApi(),
        u == null ? null : new PublicTradeDto.UntrustedRationaleDto(rationale(u.rationale())));
  }

  public static FeedItemDto feedItem(FeedItemDto f) {
    if (f == null) {
      return null;
    }
    FeedItemDto.FeedPayloadDto p = f.payload();
    FeedItemDto.FeedPayloadDto payload = p == null ? null : new FeedItemDto.FeedPayloadDto(p.side(), p.qty(),
        p.price(), p.realizedPnl(), p.amount(), p.period(), p.untrusted() == null ? null
            : new FeedItemDto.UntrustedFeedTextDto(rationale(p.untrusted().rationale()),
                rationale(p.untrusted().memo())));
    return new FeedItemDto(f.id(), f.at(), f.kind(), player(f.player()), f.symbol(), payload);
  }

  public static PlayerProfileDto profile(PlayerProfileDto p) {
    if (p == null) {
      return null;
    }
    List<PublicTradeDto> trades = p.recentTrades() == null ? null
        : p.recentTrades().stream().map(Untrusted::trade).toList();
    return new PlayerProfileDto(player(p.player()), p.ownerHandle(), p.runNo(), p.stats(), p.equity(),
        p.netContributions(), p.deposits(), p.fills(), p.winRate(), p.moneyWeightedReturnAll(), p.holdingsAsOf(),
        p.holdings(), trades);
  }
}
