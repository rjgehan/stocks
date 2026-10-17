package com.stocks.manualbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.AccountStatus;
import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.Liquidity;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.contract.marketdata.dto.ClockDto;
import com.stocks.contract.portfolio.dto.LeaderboardRowDto;
import com.stocks.contract.rules.dto.RulesDto;
import com.stocks.contract.rules.dto.RulesDto.RulesAccountDto;
import com.stocks.contract.rules.dto.RulesDto.RulesFeeDto;
import com.stocks.contract.rules.dto.RulesDto.RulesFractionalDto;
import com.stocks.contract.rules.dto.RulesDto.RulesGfvDto;
import com.stocks.contract.rules.dto.RulesDto.RulesOrdersDto;
import com.stocks.contract.rules.dto.RulesDto.RulesSessionsDto;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.AccountDto.AccountCashDto;
import com.stocks.contract.trading.dto.AccountDto.AccountGfvDto;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.testkit.TestTimes;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §20.8 prompt contents: the framing, who and when, the market line and what is accepted now for every session, the
 * league rules from {@code /rules}, the live account, holdings, open orders, fills, standing and summaries, the strict
 * reply format with the real session id, and the size budget's drop order. Rendering is deterministic.
 */
class ManualPromptBuilderTest {

  static final Instant NOW = TestTimes.START;                     // Fri 2026-10-09 10:00 ET
  static final String SESSION = "mbs_01JABCDEFGHJKMNPQRSTVWXYZ0";

  final ManualPromptBuilder builder = new ManualPromptBuilder(ManualBotsProperties.defaults());

  // ------------------------------------------------------------------------------------------- fixtures

  static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  static ClockDto clock(SessionType session, Instant endsAt, boolean earlyClose) {
    return new ClockDto(NOW, "2026-10-09T10:00:00-04:00", session, LocalDate.of(2026, 10, 9), endsAt,
        SessionType.PRE, Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-12T13:30:00Z"),
        Instant.parse("2026-10-12T20:00:00Z"), earlyClose, List.of(), List.of(), null);
  }

  static RulesDto rules(AccountType type, boolean requiresAck) {
    RulesAccountDto account = new RulesAccountDto(type, d("500.00"), "T+1", false, false, "", true,
        new RulesGfvDto(requiresAck, 3, 5, 90, "P1Y1D"));
    RulesSessionsDto sessions = new RulesSessionsDto("America/New_York", "04:00–09:30", "09:30–16:00", "16:00–20:00",
        "regular until 13:00, after-hours until 17:00", null, false);
    RulesOrdersDto orders = new RulesOrdersDto(List.of(), 90, 50, d("100000"), "", d("5"), d("102"), d("115"),
        d("5"), d("50"), "", true, new RulesFractionalDto(d("1.00"), d("0.01"), d("0.000001"), true, true, true,
        false), List.of(), null, null);
    List<RulesFeeDto> fees = List.of(
        new RulesFeeDto(FeeCode.SEC, RulesDto.FeeAppliesTo.SELL, RulesDto.FeeBasis.PRINCIPAL, d("0.0000206"), "",
            null, LocalDate.of(2025, 5, 14), null, ""),
        new RulesFeeDto(FeeCode.TAF, RulesDto.FeeAppliesTo.SELL, RulesDto.FeeBasis.SHARES, d("0.000166"), "",
            d("8.30"), LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31), ""),
        new RulesFeeDto(FeeCode.TAF, RulesDto.FeeAppliesTo.SELL, RulesDto.FeeBasis.SHARES, d("0.000195"), "",
            d("9.79"), LocalDate.of(2026, 1, 1), null, ""),
        new RulesFeeDto(FeeCode.CAT, RulesDto.FeeAppliesTo.BOTH, RulesDto.FeeBasis.SHARES, d("0.000035"), "",
            null, LocalDate.of(2027, 1, 1), null, "not yet"));
    return new RulesDto("v", NOW, account, sessions, orders, null, fees, null, null, null, List.of(), List.of());
  }

  static AccountDto account(AccountRestriction restriction) {
    return new AccountDto("acc_1", 1, AccountType.CASH, AccountStatus.ACTIVE, restriction,
        restriction == AccountRestriction.NONE ? null : Instant.parse("2026-10-14T13:30:00Z"), null, d("1234.56"),
        d("900.00"), d("0"), new AccountCashDto(d("334.56"), d("300.00"), d("34.56"), d("300.00"), d("300.00")),
        d("20.00"), d("314.56"), d("1000.00"), d("1000.00"), d("234.56"), d("0.23456"), d("1.23456"), d("-12.50"),
        d("-0.0101"), new AccountGfvDto(1, null, 3, 5), 0, NOW, DataQuality.SIMULATED, NOW);
  }

  static PositionDto position(String symbol, String qty, String marketValue, DataQuality quality) {
    return new PositionDto(symbol, symbol + " Inc.", d(qty), d(qty), d("100.00"), d("100.00").multiply(d(qty)),
        d("110.00"), d("110.00"), quality, d(marketValue), d("63.00"), d("0.0285"), d("5.00"), d("0.0123"),
        d("0.25"), true, NOW);
  }

  static OrderDto order(String id, String symbol, Side side, OrderType type, String qty, String limit) {
    return new OrderDto(id, "c-" + id, "acc_1", symbol, side, type, qty == null ? null : d(qty),
        qty == null ? d("150.00") : null, false, limit == null ? null : d(limit), null, null, null, null, null,
        TimeInForce.GTC, false, OrderStatus.WORKING, null, null, false, d("0"), d("0"), null, d("0"), null, null,
        LocalDate.of(2026, 10, 9), Instant.parse("2026-10-08T14:05:00Z"), null, null, null, null, null, null, null,
        null, null, "why", null, null, null, false, null);
  }

  static ExecutionDto fill(String symbol, Side side, String realized) {
    return new ExecutionDto("exe_1", "ord_1", symbol, side, d("2"), d("101.25"), d("202.50"), null, d("202.50"),
        realized == null ? null : d(realized), Liquidity.TOUCH, Instant.parse("2026-10-08T15:31:00Z"),
        LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), null);
  }

  static LeaderboardRowDto standing(Integer rank, String note) {
    return new LeaderboardRowDto(rank, null, rank != null, note, false, false, null, false, d("0.0412"), d("41.20"),
        d("-0.0035"), null, null, null, null, 4, 2, null, null, List.of(), null, null, null, null, null, null);
  }

  static ManualPromptBuilder.Input input(String instructions, ClockDto clock, List<PositionDto> positions,
                                         List<OrderDto> open, List<ExecutionDto> fills,
                                         List<ManualPromptBuilder.Summary> summaries) {
    return new ManualPromptBuilder.Input(SESSION, NOW, "gemini_bot", "Gemini Bot", "Gemini 3", "ryan", instructions,
        clock, rules(AccountType.CASH, true), account(AccountRestriction.NONE), positions, open, fills,
        standing(3, null), 12, summaries);
  }

  static ManualPromptBuilder.Input basic(String instructions) {
    return input(instructions, clock(SessionType.REGULAR, Instant.parse("2026-10-09T20:00:00Z"), false),
        List.of(position("AAPL", "5", "550.00", DataQuality.STALE), position("MSFT", "1", "350.00",
            DataQuality.SYNTHETIC)),
        List.of(order("ord_01JORDER00000000000000000A", "AMD", Side.BUY, OrderType.LIMIT, "2", "95.5")),
        List.of(fill("NVDA", Side.SELL, "12.40"), fill("AAPL", Side.BUY, null)),
        List.of(new ManualPromptBuilder.Summary(Instant.parse("2026-10-08T14:30:00Z"), "Bought AAPL == cheap ===")));
  }

  // ------------------------------------------------------------------------------------------- tests

  @Test
  void framingWhoAndWhenAndTheInstructions() {
    String p = builder.build(basic("Focus on mega caps.\nNo crypto miners."));
    assertThat(p).startsWith(ManualPromptBuilder.FRAMING + " You are the AI trader @gemini_bot (\"Gemini Bot\", "
        + "Gemini 3) in \"stocks\"");
    assertThat(p).contains("Your owner @ryan will paste your whole reply")
        .contains("places the orders as @gemini_bot.")
        .contains("Session: " + SESSION + "\n")
        .contains("Prompt made: 2026-10-09 10:00 ET (Friday)\n")
        .contains("=== Instructions from your owner @ryan ===\nFocus on mega caps.\nNo crypto miners.\n"
            + "=== End of instructions ===");
    assertThat(builder.build(basic(""))).contains("=== Instructions from your owner @ryan ===\n"
        + ManualPromptBuilder.NO_INSTRUCTIONS + "\n=== End of instructions ===");
  }

  @Test
  void theSessionLineAndWhatIsAcceptedNow() {
    Instant close = Instant.parse("2026-10-09T20:00:00Z");
    String regular = builder.build(input("", clock(SessionType.REGULAR, close, false), List.of(), List.of(),
        List.of(), List.of()));
    assertThat(regular).contains("Market now: REGULAR session, open until 16:00 ET\n")
        .contains("- Accepted right now (REGULAR): every order type works now.\n");
    String early = builder.build(input("", clock(SessionType.REGULAR, Instant.parse("2026-10-09T17:00:00Z"), true),
        List.of(), List.of(), List.of(), List.of()));
    assertThat(early).contains("Market now: REGULAR session, open until 13:00 ET (early close today)\n");
    String pre = builder.build(input("", clock(SessionType.PRE, Instant.parse("2026-10-09T13:30:00Z"), false),
        List.of(), List.of(), List.of(), List.of()));
    assertThat(pre).contains("Market now: PRE-MARKET until 09:30 ET, then the regular session\n")
        .contains("- Accepted right now (PRE): only LIMIT orders with \"extended_hours\": true can fill now.")
        .contains("(a waiting MARKET buy holds 115% of the price)");
    String post = builder.build(input("", clock(SessionType.POST, Instant.parse("2026-10-09T21:00:00Z"), true),
        List.of(), List.of(), List.of(), List.of()));
    assertThat(post).contains("Market now: AFTER-HOURS until 17:00 ET; next regular open Mon 2026-10-12 09:30 ET\n")
        .contains("- Accepted right now (POST): only LIMIT orders");
    String closed = builder.build(input("", clock(SessionType.CLOSED, null, false), List.of(), List.of(), List.of(),
        List.of()));
    assertThat(closed).contains("Market now: CLOSED; next session PRE at Mon 2026-10-12 04:00 ET, regular open "
            + "Mon 2026-10-12 09:30 ET\n")
        .contains("- Accepted right now (CLOSED): nothing fills until the next session.");
  }

  @Test
  void theRulesComeFromTheRulebook() {
    String p = builder.build(basic(""));
    assertThat(p).contains("- Cash account: no margin, no borrowing, no short selling, no options. You can only sell "
            + "shares you hold.\n")
        .contains("- Settlement is T+1.")
        .contains("With 3 active violations the account may only buy with settled cash, with 5 it may only sell, for "
            + "90 days; a violation expires after P1Y1D. A sell that would cause a GFV needs \"acknowledge\": "
            + "[\"GFV_RISK\"].\n")
        .contains("GTC lasts 90 days. At most 50 open orders.")
        .contains("early-close days: regular until 13:00, after-hours until 17:00) need a LIMIT order")
        .contains("MARKET + DAY only, regular session, fractionable symbols, at least $1.00 per buy")
        .contains("more than 50% from the market is rejected; more than 5% only warns. MARKET orders never fill "
            + "more than 5% through")
        .contains("regulatory fees (SEC, TAF) are charged per fill")
        .contains("in the regular session the app waits up to 3 seconds for MARKET sells to fill");
    assertThat(ManualPromptBuilder.earlyClose("Early-close days (e.g. Nov 27): regular session ends 13:00"))
        .isEqualTo("early-close days (e.g. Nov 27): regular session ends 13:00");
    assertThat(ManualPromptBuilder.accountLine(rules(AccountType.MARGIN, false).account()))
        .isEqualTo("MARGIN account; no short selling; no options.");
    ManualPromptBuilder noWait = new ManualPromptBuilder(new ManualBotsProperties(4000, 20_000, 163_840, 10, 15, 50,
        50, Duration.ofHours(1), Duration.ofHours(24), 20_000, 10, 25, 40, Duration.ZERO));
    assertThat(noWait.build(basic(""))).contains("in the regular session the app does not wait for sells to fill; "
        + "outside the regular session sells only queue");
  }

  @Test
  void theAccountHoldingsOrdersFillsAndStanding() {
    String p = builder.build(basic(""));
    assertThat(p).contains("=== Your account (live, 2026-10-09 10:00 ET) ===\n"
            + "Equity: $1,234.56 · Cash: $334.56 ($300.00 settled, $34.56 unsettled) · Buying power: $314.56\n"
            + "Open buy holds: $20.00 · Today: -$12.50 (-1.01%) · Since you started: +$234.56 (+23.46%)\n"
            + "Good-faith violations: 1\n"
            + "League standing (all-time P&L): rank 3 of 12, P&L +$41.20, return +4.12%, "
            + "-0.35% vs SPY\n")
        .contains("| Symbol | Qty | Avg cost | Last | Market value | Unrealized P&L | Today | Weight |\n")
        .contains("| AAPL | 5 | $100.00 | $110.00 (stale) | $550.00 | +$63.00 (+2.85%) | +1.23% | 25.00% |\n")
        .contains("| MSFT | 1 | $100.00 | $110.00 (est.) | $350.00 |")
        .contains("| ord_01JORDER00000000000000000A | BUY | AMD | LIMIT | 2 | $95.50 | - | GTC | no | WORKING | "
            + "2026-10-08 10:05 |\n")
        .contains("| 2026-10-08 11:31 | SELL | 2 | NVDA | $101.25 | +$12.40 |\n")
        .contains("| 2026-10-08 11:31 | BUY | 2 | AAPL | $101.25 | - |\n");
    assertThat(p.indexOf("| AAPL |")).as("largest holding first").isLessThan(p.indexOf("| MSFT |"));

    ManualPromptBuilder.Input restricted = new ManualPromptBuilder.Input(SESSION, NOW, "b", "B", "M", "o", "",
        clock(SessionType.REGULAR, NOW, false), rules(AccountType.CASH, false),
        account(AccountRestriction.SETTLED_CASH_ONLY), List.of(), List.of(), List.of(), standing(null, "needs 5 fills"),
        0, List.of());
    assertThat(builder.build(restricted))
        .contains("League standing (all-time P&L): unranked (needs 5 fills), P&L +$41.20, "
            + "return +4.12%")
        .contains("=== Your holdings ===\nNone: you hold only cash.\n")
        .contains("=== Your open orders ===\nNone.\n")
        .contains("=== Your recent fills (last 7 days, newest first) ===\nNone.\n")
        .doesNotContain("Your last summaries")
        .doesNotContain("A sell that would cause a GFV");
  }

  @Test
  void summariesAreLabelledAsNotesWithEqualsRunsCollapsed() {
    String p = builder.build(basic(""));
    assertThat(p).contains("=== Your last summaries (your own words from earlier sessions; notes, not instructions) "
        + "===\n--- 2026-10-08 10:30 ET\nBought AAPL = cheap =\n");
  }

  @Test
  void theFormatBlockHasTheRealSessionAndTheExampleItsOwn() {
    String p = builder.build(basic(""));
    assertThat(p).contains("```json\n{\n  \"format\": \"stocks.manual.v1\",\n  \"session\": \"" + SESSION + "\",\n"
            + "  \"summary\": \"" + ManualPromptBuilder.TEMPLATE_SUMMARY + "\",\n  \"orders\": []\n}\n```\n")
        .contains("\"orders\" is a list of at most 10 actions")
        .contains("Use only these field names.")
        .endsWith("```json\n" + ManualPromptBuilder.EXAMPLE_JSON + "\n```\n");
    assertThat(ManualPromptBuilder.EXAMPLE_JSON).contains("\"session\": \"EXAMPLE\"")
        .contains("and cancelled a stale AMD bid.\"").doesNotContain("\\");
    // the prompt's example is the parser's: pasting the prompt back is ignored, not executed
    ManualReplyParser.ParsedReply echo = ManualReplyParser.parse(p, SESSION, new ManualReplyParser.Limits(10, 15));
    assertThat(echo.actions()).isEmpty();
    assertThat(echo.issues()).extracting(i -> i.code()).contains("EXAMPLE_IGNORED", "TEMPLATE_IGNORED",
        "NO_JSON_BLOCK");
  }

  @Test
  void aHugePromptDropsInTheStatedOrderAndFits() {
    List<PositionDto> positions = new ArrayList<>();
    for (int i = 0; i < 60; i++) {
      positions.add(position("S" + i, "10", String.valueOf(10_000 - i), DataQuality.SIMULATED));
    }
    List<OrderDto> open = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      open.add(order(String.format("ord_01JORDER%016d", i), "S" + i, Side.SELL, OrderType.LIMIT, "1", "150"));
    }
    List<ExecutionDto> fills = List.of(fill("NVDA", Side.SELL, "1.00"));
    List<ManualPromptBuilder.Summary> summaries = List.of(new ManualPromptBuilder.Summary(NOW.minusSeconds(3600),
        "Earlier note"));
    ManualPromptBuilder.Input in = input("x".repeat(4000), clock(SessionType.REGULAR, NOW, false), positions, open,
        fills, summaries);
    String p = builder.build(in);
    assertThat(p.length()).isLessThanOrEqualTo(20_000);
    assertThat(p).contains("(Left out: this prompt is at its size limit.)")                         // 1. fills
        .doesNotContain("Earlier note")                                                           // 2. summaries
        .contains("…and 25 more open orders (not shown)");                                       // 3. open orders
    assertThat(p).contains("| S59 |").as("holdings are cut only after open orders");
    assertThat(p).contains(String.format("ord_01JORDER%016d", 24)).doesNotContain(String.format("ord_01JORDER%016d",
        25));

    // a smaller budget keeps cutting: holdings beyond 40, open orders beyond 10, holdings beyond 20
    ManualPromptBuilder tight = new ManualPromptBuilder(new ManualBotsProperties(4000, 20_000, 163_840, 10, 15, 50,
        50, Duration.ofHours(1), Duration.ofHours(24), 13_000, 10, 25, 40, Duration.ofSeconds(3)));
    String cut = tight.build(in);
    assertThat(cut.length()).isLessThanOrEqualTo(13_000);
    assertThat(cut).contains("…and 40 more open orders (not shown)").contains("…and 40 more\n")
        .doesNotContain("| S20 |").contains("| S19 |");
  }

  @Test
  void renderingIsDeterministic() {
    assertThat(builder.build(basic("Same input"))).isEqualTo(builder.build(basic("Same input")));
  }

  @Test
  void formatting() {
    assertThat(ManualPromptBuilder.money(d("1234567.891"))).isEqualTo("$1,234,567.89");
    assertThat(ManualPromptBuilder.money(d("-12.5"))).isEqualTo("-$12.50");
    assertThat(ManualPromptBuilder.signedMoney(d("63"))).isEqualTo("+$63.00");
    assertThat(ManualPromptBuilder.signedMoney(d("0"))).isEqualTo("$0.00");
    assertThat(ManualPromptBuilder.pct(d("0.02854"))).isEqualTo("+2.85%");
    assertThat(ManualPromptBuilder.pct(d("-0.1"))).isEqualTo("-10.00%");
    assertThat(ManualPromptBuilder.price(d("0.1234"))).isEqualTo("$0.1234");
    assertThat(ManualPromptBuilder.price(d("1412.5"))).isEqualTo("$1,412.50");
    assertThat(ManualPromptBuilder.qty(d("10.500000"))).isEqualTo("10.5");
    assertThat(ManualPromptBuilder.age(Duration.ofMinutes(130))).isEqualTo("2 h 10 min");
  }

  /** §29: the crypto line follows the rulebook's crypto section and the clock. */
  @Test
  void cryptoPairsAreExplainedWhileCryptoIsOn() {
    RulesDto.RulesCryptoDto on = new RulesDto.RulesCryptoDto(true, List.of("BTC/USD", "ETH/USD"), "24/7", "", "",
        "", "", "", "");
    assertThat(ManualPromptBuilder.cryptoLine(on)).contains("Crypto pairs (BTC/USD, ETH/USD) trade 24/7")
        .contains("\"BTC/USD\"").contains("pay no fees");
    assertThat(ManualPromptBuilder.cryptoLine(new RulesDto.RulesCryptoDto(false, List.of(), "", "", "", "", "", "",
        ""))).contains("turned off");
    assertThat(ManualPromptBuilder.cryptoLine(null)).isEmpty();
  }
}
