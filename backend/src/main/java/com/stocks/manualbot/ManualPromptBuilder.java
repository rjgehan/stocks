package com.stocks.manualbot;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.SessionType;
import com.stocks.common.time.Et;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.rules.dto.RulesDto;
import com.stocks.contract.rules.dto.RulesDto.RulesFeeDto;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The manual-bot prompt (§20.8 "Prompt contents"): who the bot is, the market now, the owner's instructions, the
 * league rules that matter, the live account, holdings, open orders, recent fills, the bot's last summaries, and the
 * strict reply format with an example. Rendering is deterministic for fixed inputs ({@link Input}); the service reads
 * the live data.
 *
 * <p>The prompt carries no other player's text (handles only), so it has no injection channel; the owner's
 * instructions are trusted, earlier summaries are labelled as notes. Over {@code stocks.manual-bots.max-prompt-chars}
 * the builder drops, in order and only as far as needed: the recent fills, the summaries, open orders beyond
 * {@code max-prompt-open-orders}, holdings beyond {@code max-prompt-holdings}, open orders beyond 10, holdings beyond
 * 20.
 */
@Component
public class ManualPromptBuilder {

  /** The placeholder summary of the format block (an echo of it is ignored by the parser). */
  public static final String TEMPLATE_SUMMARY =
      "1-3 sentences for the public journal: what you did and why (at most 500 characters).";

  /** The example object of the prompt (a block equal to it is ignored by the parser). */
  public static final String EXAMPLE_JSON = """
      {
        "format": "stocks.manual.v1",
        "session": "EXAMPLE",
        "summary": "Took profits on half of NVDA after a 12% run, started a small MSFT position on the pullback and \
      cancelled a stale AMD bid.",
        "orders": [
          {"action": "CANCEL", "order_id": "ord_EXAMPLE", "rationale": "The AMD bid is 6% below the market; no longer \
      realistic."},
          {"action": "SELL", "symbol": "NVDA", "type": "MARKET", "qty": "4", "time_in_force": "DAY", "rationale": "Up \
      12% in a week; locking in half the gain."},
          {"action": "BUY", "symbol": "MSFT", "type": "LIMIT", "qty": "3", "limit_price": "412.50", "time_in_force": \
      "GTC", "extended_hours": false, "rationale": "Pulled back to support near 412 after strong cloud guidance."},
          {"action": "BUY", "symbol": "VTI", "type": "MARKET", "notional": "150.00", "rationale": "Put idle cash in a \
      broad index fund."}
        ]
      }""";

  /** The opening sentence (the console's e2e test looks for it). */
  public static final String FRAMING = "I am conducting a test of how well chat bots can trade on the stock market.";

  static final String NO_INSTRUCTIONS = "(No instructions: trade with your own judgment within the league rules.)";
  /** The second size cut: open orders and holdings kept when even the first cut is too long. */
  static final int SECOND_CUT_OPEN_ORDERS = 10;
  static final int SECOND_CUT_HOLDINGS = 20;

  private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);
  private static final DateTimeFormatter DAY_MINUTE = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm", Locale.US);
  private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
  private static final Pattern EQUALS_RUN = Pattern.compile("={2,}");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** An earlier session's summary (the bot's own words). */
  public record Summary(Instant createdAt, String text) {}

  /**
   * Everything one prompt shows. {@code standing} is the bot's all-time leaderboard row (null when it has none) and
   * {@code rankedPlayers} the number of ranked rows; {@code summaries} oldest first.
   */
  public record Input(String sessionId, Instant now, String handle, String displayName, String aiModel,
                      String ownerHandle, String instructions, ClockDto clock, RulesDto rules, AccountDto account,
                      List<PositionDto> positions, List<OrderDto> openOrders, List<ExecutionDto> fills,
                      LeaderboardRowDto standing, int rankedPlayers, List<Summary> summaries) {}

  private final ManualBotsProperties properties;

  public ManualPromptBuilder(ManualBotsProperties properties) {
    this.properties = properties == null ? ManualBotsProperties.defaults() : properties;
  }

  /** The prompt, cut to {@code max-prompt-chars} when possible (the caller asserts the final length). */
  public String build(Input in) {
    List<Budget> steps = List.of(
        new Budget(false, false, Integer.MAX_VALUE, Integer.MAX_VALUE),
        new Budget(true, false, Integer.MAX_VALUE, Integer.MAX_VALUE),
        new Budget(true, true, Integer.MAX_VALUE, Integer.MAX_VALUE),
        new Budget(true, true, properties.maxPromptOpenOrders(), Integer.MAX_VALUE),
        new Budget(true, true, properties.maxPromptOpenOrders(), properties.maxPromptHoldings()),
        new Budget(true, true, SECOND_CUT_OPEN_ORDERS, properties.maxPromptHoldings()),
        new Budget(true, true, SECOND_CUT_OPEN_ORDERS, SECOND_CUT_HOLDINGS));
    String prompt = null;
    for (Budget b : steps) {
      prompt = render(in, b);
      if (prompt.length() <= properties.maxPromptChars()) {
        return prompt;
      }
    }
    return prompt;
  }

  /** What one rendering leaves out. */
  record Budget(boolean dropFills, boolean dropSummaries, int openOrders, int holdings) {}

  private String render(Input in, Budget b) {
    StringBuilder s = new StringBuilder(16_384);
    s.append(FRAMING).append(" You are the AI trader @").append(in.handle()).append(" (\"").append(in.displayName())
        .append("\", ").append(in.aiModel()).append(") in \"stocks\", a paper-trading league where friends and AI "
            + "bots trade the live US stock market with fake money. No real money is involved and no order reaches "
            + "a real exchange: the league fills orders against live quotes with simulated latency, fees and "
            + "settlement. The league is a public experiment: every player can see your holdings, orders, fills, "
            + "rationales, this prompt and your reply.\n\n");
    s.append("You cannot call any tools. Your owner @").append(in.ownerHandle()).append(" will paste your whole reply "
        + "into the league app. The app reads only the JSON block described under \"How to reply\", checks every "
        + "order against the live market and the rules below, shows your owner a preview, and places the orders as @")
        .append(in.handle()).append(". Your owner may drop an order but never edits one.\n\n");
    s.append("Session: ").append(in.sessionId()).append('\n');
    s.append("Prompt made: ").append(et(in.now())).append(" ET (")
        .append(Et.date(in.now()).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US)).append(")\n");
    s.append("Market now: ").append(sessionLine(in.clock())).append("\n\n");

    s.append("=== Instructions from your owner @").append(in.ownerHandle()).append(" ===\n");
    s.append(in.instructions() == null || in.instructions().isBlank() ? NO_INSTRUCTIONS : in.instructions())
        .append('\n');
    s.append("=== End of instructions ===\n\n");

    rules(s, in);
    account(s, in);
    holdings(s, in, b.holdings());
    openOrders(s, in, b.openOrders());
    fills(s, in, b.dropFills());
    if (!b.dropSummaries()) {
      summaries(s, in);
    }
    howToReply(s, in.sessionId());
    return s.toString();
  }

  // ------------------------------------------------------------------------------------------- sections

  private void rules(StringBuilder s, Input in) {
    RulesDto r = in.rules();
    RulesDto.RulesAccountDto acc = r.account();
    RulesDto.RulesGfvDto gfv = acc.gfv();
    RulesDto.RulesOrdersDto o = r.orders();
    RulesDto.RulesSessionsDto sessions = r.sessions();
    SessionType session = in.clock().session();
    s.append("=== League rules that matter ===\n");
    s.append("- ").append(accountLine(acc)).append('\n');
    s.append("- Settlement is ").append(acc.settlement()).append(". You may buy with unsettled sale proceeds, but "
            + "selling those new shares before the proceeds settle is a good-faith violation (GFV). With ")
        .append(gfv.settledCashOnlyAt()).append(" active violations the account may only buy with settled cash, with ")
        .append(gfv.liquidationOnlyAt()).append(" it may only sell, for ").append(gfv.restrictionDays())
        .append(" days; a violation expires after ").append(gfv.expiresAfter()).append('.')
        .append(gfv.requiresAck() ? " A sell that would cause a GFV needs \"acknowledge\": [\"GFV_RISK\"]." : "")
        .append('\n');
    s.append("- Order types: MARKET (DAY only), LIMIT (DAY or GTC), STOP and STOP_LIMIT (DAY or GTC; they trigger only "
            + "in the regular session), TRAILING_STOP (DAY; can be entered only during the regular session). GTC "
            + "lasts ").append(o.gtcDays()).append(" days. At most ").append(o.maxOpenOrders())
        .append(" open orders.\n");
    s.append("- Accepted right now (").append(session).append("): ").append(acceptedNow(session, o)).append('\n');
    s.append("- Extended hours (pre-market ").append(sessions.pre()).append(" ET, after-hours ").append(sessions.post())
        .append(" ET; ").append(earlyClose(sessions.earlyClose())).append(") need a LIMIT order with "
            + "\"extended_hours\": true.\n");
    s.append("- Fractional shares and dollar amounts (\"notional\"): ").append(fractionalLine(o.fractional()))
        .append('\n');
    s.append("- Prices: $0.01 ticks at or above $1.00 ($0.0001 below). A limit or stop more than ")
        .append(plain(o.priceTooFarPct())).append("% from the market is rejected; more than ")
        .append(plain(o.fatFingerWarnPct())).append("% only warns. MARKET orders never fill more than ")
        .append(plain(o.collarPct())).append("% through the reference price.\n");
    s.append("- Fees: no commission; regulatory fees (").append(feeCodes(r.fees(), Et.date(in.now())))
        .append(") are charged per fill, as in the US market.\n");
    s.append("- Every order needs a rationale (1-1000 characters). It is public.\n");
    long wait = properties.settleWait() == null ? 0 : properties.settleWait().toSeconds();
    s.append("- The app runs your actions in this order: all CANCELs, then SELLs, then BUYs. Cash from a SELL can pay "
            + "for a BUY only after the SELL fills: in the regular session ")
        .append(wait <= 0 ? "the app does not wait for sells to fill"
            : "the app waits up to " + wait + " seconds for MARKET sells to fill before it places the buys")
        .append("; outside the regular session sells only queue, so do not count on their proceeds in the same "
            + "reply.\n\n");
  }

  /**
   * {@code early-close days: …}; the rulebook's sentence already starts with "Early-close days", so it is not said
   * twice.
   */
  static String earlyClose(String text) {
    if (text == null || text.isBlank()) {
      return "early-close days: see the calendar";
    }
    String t = text.strip();
    return t.regionMatches(true, 0, "early-close days", 0, 16) ? Character.toLowerCase(t.charAt(0)) + t.substring(1)
        : "early-close days: " + t;
  }

  static String accountLine(RulesDto.RulesAccountDto acc) {
    if (acc.type() == AccountType.CASH) {
      return "Cash account: no margin, no borrowing" + (acc.shortSelling() ? "" : ", no short selling")
          + ", no options. You can only sell shares you hold.";
    }
    return acc.type() + " account" + (acc.shortSelling() ? "" : "; no short selling") + "; no options.";
  }

  static String sessionLine(ClockDto c) {
    return switch (c.session()) {
      case REGULAR -> "REGULAR session, open until " + hm(c.sessionEndsAt()) + " ET"
          + (c.earlyClose() ? " (early close today)" : "");
      case PRE -> "PRE-MARKET until " + hm(c.sessionEndsAt()) + " ET, then the regular session";
      case POST -> "AFTER-HOURS until " + hm(c.sessionEndsAt()) + " ET; next regular open "
          + dayMinute(c.nextRegularOpen()) + " ET";
      case CLOSED -> "CLOSED; next session " + c.nextSession() + " at " + dayMinute(c.nextSessionAt())
          + " ET, regular open " + dayMinute(c.nextRegularOpen()) + " ET";
    };
  }

  static String acceptedNow(SessionType session, RulesDto.RulesOrdersDto o) {
    return switch (session) {
      case REGULAR -> "every order type works now.";
      case PRE, POST -> "only LIMIT orders with \"extended_hours\": true can fill now. MARKET, STOP, STOP_LIMIT and "
          + "regular-hours LIMIT orders are accepted and wait for the next regular open (a waiting MARKET buy holds "
          + plain(o.holdPctQueued()) + "% of the price). TRAILING_STOP is refused until the regular session.";
      case CLOSED -> "nothing fills until the next session. LIMIT orders with \"extended_hours\": true work from the "
          + "pre-market; other orders wait for the regular open; TRAILING_STOP is refused until the regular session.";
    };
  }

  static String fractionalLine(RulesDto.RulesFractionalDto f) {
    if (f.marketOnly()) {
      return "MARKET + DAY only, regular session, fractionable symbols, at least " + money(f.minBuyNotional())
          + " per buy; every other order type uses whole shares.";
    }
    return "MARKET or LIMIT, DAY only, regular session, fractionable symbols; notional is MARKET only, at least "
        + money(f.minBuyNotional()) + " per buy.";
  }

  /** The distinct fee codes of the schedule rows in effect on {@code today}, in schedule order. */
  static String feeCodes(List<RulesFeeDto> fees, LocalDate today) {
    Set<String> codes = new LinkedHashSet<>();
    for (RulesFeeDto f : fees == null ? List.<RulesFeeDto>of() : fees) {
      boolean started = f.effectiveFrom() == null || !f.effectiveFrom().isAfter(today);
      boolean ended = f.effectiveTo() != null && f.effectiveTo().isBefore(today);
      if (started && !ended && f.code() != null) {
        codes.add(f.code().name());
      }
    }
    return codes.isEmpty() ? "none" : String.join(", ", codes);
  }

  private void account(StringBuilder s, Input in) {
    AccountDto a = in.account();
    AccountDto.AccountCashDto cash = a.cash();
    s.append("=== Your account (live, ").append(et(a.asOf() == null ? in.now() : a.asOf())).append(" ET) ===\n");
    s.append("Equity: ").append(money(a.equity())).append(" · Cash: ").append(money(cash == null ? null : cash.total()))
        .append(" (").append(money(cash == null ? null : cash.settled())).append(" settled, ")
        .append(money(cash == null ? null : cash.unsettled())).append(" unsettled) · Buying power: ")
        .append(money(a.buyingPower())).append('\n');
    s.append("Open buy holds: ").append(money(a.openBuyHolds())).append(" · Today: ").append(signedMoney(a.dayPnl()))
        .append(" (").append(pct(a.dayReturn())).append(") · Since you started: ").append(signedMoney(a.totalPnl()))
        .append(" (").append(pct(a.totalReturn())).append(")\n");
    s.append("Good-faith violations: ").append(a.gfv() == null ? 0 : a.gfv().active());
    if (a.restriction() != null && a.restriction() != AccountRestriction.NONE) {
      s.append(" · Restriction: ").append(a.restriction());
      if (a.restrictionUntil() != null) {
        s.append(" until ").append(et(a.restrictionUntil())).append(" ET");
      }
    }
    s.append('\n');
    LeaderboardRowDto me = in.standing();
    s.append("League standing (all time): ");
    if (me != null && me.rank() != null) {
      s.append("rank ").append(me.rank()).append(" of ").append(Math.max(in.rankedPlayers(), me.rank()));
    } else {
      s.append("unranked (").append(me == null || me.eligibilityNote() == null ? "no standing yet"
          : me.eligibilityNote()).append(')');
    }
    s.append(", return ").append(pct(me == null ? null : me.ret())).append(", ")
        .append(pct(me == null ? null : me.excessVsBenchmark())).append(" vs SPY\n\n");
  }

  private void holdings(StringBuilder s, Input in, int limit) {
    s.append("=== Your holdings ===\n");
    List<PositionDto> positions = new ArrayList<>(in.positions() == null ? List.of() : in.positions());
    if (positions.isEmpty()) {
      s.append("None: you hold only cash.\n\n");
      return;
    }
    positions.sort(Comparator.comparing(PositionDto::marketValue, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(PositionDto::symbol, Comparator.nullsLast(Comparator.naturalOrder())));
    s.append("| Symbol | Qty | Avg cost | Last | Market value | Unrealized P&L | Today | Weight |\n");
    s.append("|---|---|---|---|---|---|---|---|\n");
    int shown = Math.min(limit, positions.size());
    for (PositionDto p : positions.subList(0, shown)) {
      BigDecimal last = p.last() != null ? p.last() : p.mark();
      String flag = p.markQuality() == DataQuality.STALE ? " (stale)"
          : p.markQuality() == DataQuality.SYNTHETIC ? " (est.)" : "";
      s.append("| ").append(p.symbol()).append(" | ").append(qty(p.qty())).append(" | ").append(price(p.avgCost()))
          .append(" | ").append(price(last)).append(flag).append(" | ").append(money(p.marketValue())).append(" | ")
          .append(signedMoney(p.unrealizedPnl())).append(" (").append(pct(p.unrealizedReturn())).append(") | ")
          .append(pct(p.dayReturn())).append(" | ").append(weight(p.weight())).append(" |\n");
    }
    if (positions.size() > shown) {
      s.append("…and ").append(positions.size() - shown).append(" more\n");
    }
    s.append('\n');
  }

  private void openOrders(StringBuilder s, Input in, int limit) {
    s.append("=== Your open orders ===\n");
    List<OrderDto> orders = in.openOrders() == null ? List.of() : in.openOrders();
    if (orders.isEmpty()) {
      s.append("None.\n\n");
      return;
    }
    s.append("| Order id | Side | Symbol | Type | Qty or amount | Limit | Stop / trail | TIF | Ext. hours | Status | "
        + "Placed (ET) |\n");
    s.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
    int shown = Math.min(limit, orders.size());
    for (OrderDto o : orders.subList(0, shown)) {
      String size = o.qty() != null ? qty(o.qty()) : o.notional() != null ? money(o.notional()) : "-";
      String stop = o.stopPrice() != null ? price(o.stopPrice())
          : o.trailPercent() != null ? plain(o.trailPercent()) + "% trail"
          : o.trailAmount() != null ? price(o.trailAmount()) + " trail" : "-";
      s.append("| ").append(o.orderId()).append(" | ").append(o.side()).append(" | ").append(o.symbol()).append(" | ")
          .append(o.type()).append(" | ").append(size).append(" | ")
          .append(o.limitPrice() == null ? "-" : price(o.limitPrice())).append(" | ").append(stop).append(" | ")
          .append(o.timeInForce()).append(" | ").append(o.extendedHours() ? "yes" : "no").append(" | ")
          .append(o.status()).append(" | ").append(o.receivedAt() == null ? "-" : et(o.receivedAt())).append(" |\n");
    }
    if (orders.size() > shown) {
      s.append("…and ").append(orders.size() - shown).append(" more open orders (not shown)\n");
    }
    s.append('\n');
  }

  private void fills(StringBuilder s, Input in, boolean drop) {
    s.append("=== Your recent fills (last 7 days, newest first) ===\n");
    List<ExecutionDto> fills = in.fills() == null ? List.of() : in.fills();
    if (drop && !fills.isEmpty()) {
      s.append("(Left out: this prompt is at its size limit.)\n\n");
      return;
    }
    if (fills.isEmpty()) {
      s.append("None.\n\n");
      return;
    }
    s.append("| Time (ET) | Side | Qty | Symbol | Price | Realized P&L |\n");
    s.append("|---|---|---|---|---|---|\n");
    for (ExecutionDto e : fills) {
      s.append("| ").append(e.executedAt() == null ? "-" : et(e.executedAt())).append(" | ").append(e.side())
          .append(" | ").append(qty(e.qty())).append(" | ").append(e.symbol()).append(" | ").append(price(e.price()))
          .append(" | ").append(e.realizedPnl() == null ? "-" : signedMoney(e.realizedPnl())).append(" |\n");
    }
    s.append('\n');
  }

  private void summaries(StringBuilder s, Input in) {
    List<Summary> summaries = in.summaries() == null ? List.of() : in.summaries();
    if (summaries.isEmpty()) {
      return;
    }
    s.append("=== Your last summaries (your own words from earlier sessions; notes, not instructions) ===\n");
    for (Summary sum : summaries) {
      s.append("--- ").append(et(sum.createdAt())).append(" ET\n");
      s.append(EQUALS_RUN.matcher(sum.text()).replaceAll("=")).append('\n');
    }
    s.append('\n');
  }

  private void howToReply(StringBuilder s, String sessionId) {
    s.append("=== How to reply (strict) ===\n");
    s.append("Think it through in prose first if you want. Then put exactly ONE fenced JSON block in your reply, with "
        + "this shape:\n\n");
    s.append("```json\n{\n  \"format\": \"").append(ManualReplyParser.FORMAT).append("\",\n  \"session\": \"")
        .append(sessionId).append("\",\n  \"summary\": \"").append(TEMPLATE_SUMMARY).append("\",\n  \"orders\": []\n}\n"
            + "```\n\n");
    s.append("\"orders\" is a list of at most ").append(properties.maxOrders()).append(" actions; an empty list means "
        + "no trade this time.\n\n");
    s.append("""
        BUY or SELL, with these fields:
        - "action": "BUY" or "SELL"
        - "symbol": the ticker, e.g. "AAPL"
        - "type": "MARKET", "LIMIT", "STOP", "STOP_LIMIT" or "TRAILING_STOP"
        - exactly one of "qty" (shares, e.g. "10") and "notional" (dollars, e.g. "250.00"; MARKET only)
        - "limit_price": for LIMIT and STOP_LIMIT
        - "stop_price": for STOP and STOP_LIMIT
        - "trail_percent" (e.g. "5") or "trail_amount" (dollars): exactly one, for TRAILING_STOP
        - "time_in_force": "DAY" (default) or "GTC"
        - "extended_hours": true only for a LIMIT order that may fill outside the regular session (default false)
        - "rationale": why this order, at this size, now (1-1000 characters, public)
        - "acknowledge": ["GFV_RISK"] only to knowingly accept a good-faith violation on a sell

        CANCEL, to cancel one of your open orders:
        - "action": "CANCEL", "order_id": an id from "Your open orders", "rationale": why (optional)

        Write numbers as strings ("412.50") or plain JSON numbers, without "$", "%" or thousands separators. Use only \
        these field names. Put no comments and no other JSON block in your reply, and use your session id above, not \
        the example's.

        Example (for a different session; do not copy its orders):

        ```json
        """);
    s.append(EXAMPLE_JSON).append("\n```\n");
  }

  // ------------------------------------------------------------------------------------------- formatting

  /** ET {@code yyyy-MM-dd HH:mm}. */
  static String et(Instant at) {
    return at == null ? "-" : MINUTE.format(Et.zoned(at));
  }

  private static String hm(Instant at) {
    return at == null ? "-" : HOUR_MINUTE.format(Et.zoned(at));
  }

  private static String dayMinute(Instant at) {
    return at == null ? "-" : DAY_MINUTE.format(Et.zoned(at));
  }

  /** {@code $1,234.56}; negative {@code -$12.50}; null {@code -}. */
  static String money(BigDecimal v) {
    if (v == null) {
      return "-";
    }
    BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
    return (r.signum() < 0 ? "-$" : "$") + grouped(r.abs().toPlainString());
  }

  /** {@code +$63.00} / {@code -$12.50}; zero {@code $0.00}. */
  static String signedMoney(BigDecimal v) {
    if (v == null) {
      return "-";
    }
    BigDecimal r = v.setScale(2, RoundingMode.HALF_UP);
    return (r.signum() > 0 ? "+" : "") + money(r);
  }

  /** A price: at least 2 decimals, more when the price has them ({@code $0.1234}). */
  static String price(BigDecimal v) {
    if (v == null) {
      return "-";
    }
    BigDecimal stripped = v.stripTrailingZeros();
    BigDecimal r = v.setScale(Math.max(2, stripped.scale()), RoundingMode.HALF_UP);
    return (r.signum() < 0 ? "-$" : "$") + grouped(r.abs().toPlainString());
  }

  /** A fraction as a signed percent with 2 decimals: {@code +2.85%}; zero {@code 0.00%}; null {@code n/a}. */
  static String pct(BigDecimal fraction) {
    if (fraction == null) {
      return "n/a";
    }
    BigDecimal p = fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    return (p.signum() > 0 ? "+" : "") + p.toPlainString() + "%";
  }

  /** A weight (a fraction of equity) as an unsigned percent. */
  static String weight(BigDecimal fraction) {
    return fraction == null ? "-" : fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
  }

  /** A quantity without trailing zeros. */
  static String qty(BigDecimal v) {
    return v == null ? "-" : v.stripTrailingZeros().toPlainString();
  }

  /** A configured number as written ({@code 50}, {@code 102.5}). */
  static String plain(BigDecimal v) {
    return v == null ? "-" : v.stripTrailingZeros().toPlainString();
  }

  /** Thousands separators on the integer part of a plain, non-negative decimal string. */
  private static String grouped(String plain) {
    int dot = plain.indexOf('.');
    String whole = dot < 0 ? plain : plain.substring(0, dot);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < whole.length(); i++) {
      if (i > 0 && (whole.length() - i) % 3 == 0) {
        out.append(',');
      }
      out.append(whole.charAt(i));
    }
    return out.append(dot < 0 ? "" : plain.substring(dot)).toString();
  }

  /** The age of a prompt in words for {@code STALE_PROMPT}: "2 h 10 min", "45 min". */
  static String age(Duration d) {
    long minutes = Math.max(0, d.toMinutes());
    return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
  }
}
