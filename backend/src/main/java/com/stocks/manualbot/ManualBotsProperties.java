package com.stocks.manualbot;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Manual-bot settings, {@code stocks.manual-bots} (§20.8, §23.1). Every key has a safe default, so production needs no
 * new configuration.
 *
 * @param maxInstructionsChars the owner's instructions, after cleaning
 * @param maxReplyChars        a pasted reply ({@code reply.length()})
 * @param maxReplyBodyBytes    the largest {@code POST …/reply} body
 * @param maxOrders            rows a reply may hold before it is refused ({@code TOO_MANY_ORDERS})
 * @param maxStoredRows        rows kept (shown, none executable) when a reply has too many
 * @param maxIssues            session-level issues kept
 * @param sessionsPerDay       prompts per bot per ET day
 * @param staleAfter           a reply to an older prompt gets {@code STALE_PROMPT}
 * @param expireAfter          a prompt older than this takes no reply and places nothing
 * @param maxPromptChars       the prompt's size budget (§20.8 drop order)
 * @param recentFills          fills shown in the prompt (last 7 days)
 * @param maxPromptOpenOrders  open orders shown before the first cut
 * @param maxPromptHoldings    holdings shown before the first cut
 * @param settleWait           how long execute waits for requested cancels and REGULAR-session MARKET sells before the
 *                             next group ({@code PT0S} in tests)
 */
@ConfigurationProperties("stocks.manual-bots")
public record ManualBotsProperties(@DefaultValue("4000") int maxInstructionsChars,
                                   @DefaultValue("20000") int maxReplyChars,
                                   @DefaultValue("163840") int maxReplyBodyBytes,
                                   @DefaultValue("10") int maxOrders,
                                   @DefaultValue("15") int maxStoredRows,
                                   @DefaultValue("50") int maxIssues,
                                   @DefaultValue("50") int sessionsPerDay,
                                   @DefaultValue("1h") Duration staleAfter,
                                   @DefaultValue("24h") Duration expireAfter,
                                   @DefaultValue("20000") int maxPromptChars,
                                   @DefaultValue("10") int recentFills,
                                   @DefaultValue("25") int maxPromptOpenOrders,
                                   @DefaultValue("40") int maxPromptHoldings,
                                   @DefaultValue("3s") Duration settleWait) {

  /** The defaults, for tests and components built without Spring. */
  public static ManualBotsProperties defaults() {
    return new ManualBotsProperties(4000, 20_000, 163_840, 10, 15, 50, 50, Duration.ofHours(1), Duration.ofHours(24),
        20_000, 10, 25, 40, Duration.ofSeconds(3));
  }
}
