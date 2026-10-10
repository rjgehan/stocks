package com.stocks.rules;

import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.rules.dto.RulesDto;
import com.stocks.contract.rules.dto.RulesDto.OrderMatrixRowDto;
import com.stocks.contract.rules.dto.RulesDto.RulesAccountDto;
import com.stocks.contract.rules.dto.RulesDto.RulesExecutionDto;
import com.stocks.contract.rules.dto.RulesDto.RulesFeeDto;
import com.stocks.contract.rules.dto.RulesDto.RulesFractionalDto;
import com.stocks.contract.rules.dto.RulesDto.RulesGfvDto;
import com.stocks.contract.rules.dto.RulesDto.RulesLeaderboardDto;
import com.stocks.contract.rules.dto.RulesDto.RulesOrdersDto;
import com.stocks.contract.rules.dto.RulesDto.RulesSessionsDto;
import com.stocks.contract.rules.dto.RulesDto.RulesSocialDto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rulebook bots read first (§18.3 {@code Rules}; {@code GET /api/v1/rules}, MCP {@code get_rules}; mirrored for
 * people in {@code docs/rules.md}). Built from the live configuration, never hard-coded twice:
 * <ul>
 *   <li>order and account rules from {@code stocks.rules} / {@code stocks.engine} ({@link RulesConfig});</li>
 *   <li>league settings from {@link SettingsService} ({@code app_setting}, admin-editable, §23.4), with the §23.4
 *       defaults while WS1's settings are still a Day-0 stub;</li>
 *   <li>fees from {@code fee_schedule}: every row in force today or scheduled later (§13);</li>
 *   <li>rate limits from {@code stocks.ratelimit} (§17.5); data sources from the market-data profile (§15).</li>
 * </ul>
 * {@code version} is a hash of everything except {@code asOf}, so it changes exactly when a published rule changes;
 * a bot can cache the rulebook by version.
 */
@Service
public class RulesService {

  private final Environment environment;
  private final StocksProperties properties;
  private final SettingsService settings;
  private final JdbcClient jdbc;
  private final TimeSource time;
  private final JsonMapper jsonMapper;

  public RulesService(Environment environment, StocksProperties properties, SettingsService settings,
                      JdbcClient jdbc, TimeSource time, JsonMapper jsonMapper) {
    this.environment = environment;
    this.properties = properties;
    this.settings = settings;
    this.jdbc = jdbc;
    this.time = time;
    this.jsonMapper = jsonMapper;
  }

  /** The rulebook as of now. */
  public RulesDto rules() {
    RulesConfig config = RulesConfig.from(environment);
    RulesConfig.Rules r = config.rules();
    LocalDate today = time.todayEt();

    RulesAccountDto account = new RulesAccountDto(AccountType.CASH, startingCash(), "T+1", r.pdt().enforced(), false,
        "Cash account: buying power = total cash (settled + unsettled sale proceeds) − open buy-order holds. Under "
            + "SETTLED_CASH_ONLY it is settled cash − holds (never below 0); under LIQUIDATION_ONLY or FROZEN it is 0.",
        true, new RulesGfvDto(r.gfv().requiresAck(), r.gfv().settledCashOnlyAt(), r.gfv().liquidationOnlyAt(),
            r.gfv().restrictionDays(), "P1Y1D"));

    RulesSessionsDto sessions = new RulesSessionsDto("America/New_York", "04:00–09:30", "09:30–16:00",
        "16:00–20:00", "Early-close days (e.g. Nov 27 and Dec 24, 2026): regular session ends 13:00, after-hours "
            + "17:00", liveIexWindow(config), false);

    RulesOrdersDto orders = new RulesOrdersDto(availableMatrix(r), r.gtcDays(), r.maxOpenOrders(), r.maxOrderQty(),
        "$0.01 for prices at or above $1.00; $0.0001 below $1.00 (Reg NMS 612). Limit, stop and trail amounts must "
            + "be on tick.",
        r.marketCollarPct(), r.holdPctRegular(), r.holdPctQueued(), r.fatFingerWarnPct(), r.priceTooFarPct(),
        "^[A-Za-z0-9._:-]{1,64}$", botsRequireRationale(),
        new RulesFractionalDto(r.fractional().minBuyNotional(), r.fractional().minSellNotional(),
            r.fractional().minQty(), !r.fractional().allowLimit(), true, true, false));

    RulesExecutionDto execution = new RulesExecutionDto(latency(config.engine()), qualityLadder(), freshness(r),
        "Marketable orders fill at the touch up to the displayed size, then walk the book in steps of half a spread "
            + "(whole shares; at most 10 levels and never beyond the limit or the 5% market collar). Fractional and "
            + "notional orders fill in one piece at the touch. Resting limits fill at the limit price when the "
            + "quote crosses it or a print trades through it.",
        false);

    Map<String, Object> rateLimits = rateLimits();

    RulesLeaderboardDto leaderboard = new RulesLeaderboardDto(
        "Time-weighted return from unit values (TWR = U(end) / U(start) − 1): deposits, withdrawals and resets never "
            + "move it.",
        "Net-contribution P&L: change in (equity − net deposits) over the period; it sums across resets.",
        "20:00 ET (17:00 on early-close days)", minFills(), minSymbols(),
        "Equal returns (within 1e-8) rank by P&L, then by who opened their first account earlier.", "SPY",
        "A reset never erases a loss from any leaderboard period: the new run continues the old run's unit value. "
            + "Only a new period starts from 0%.");

    RulesSocialDto social = new RulesSocialDto(tradeFeedDelay().toString(), holdingsVisibility());

    RulesDto content = new RulesDto(null, null, account, sessions, orders, execution, fees(today), rateLimits,
        leaderboard, social, dataSources(config), fairness());
    String version = version(content);
    return new RulesDto(version, time.now(), content.account(), content.sessions(), content.orders(),
        content.execution(), content.fees(), content.rateLimits(), content.leaderboard(), content.social(),
        content.dataSources(), content.fairness());
  }

  // ------------------------------------------------------------------------------------------- orders

  /**
   * The order types this release accepts. M1 ships MARKET and LIMIT; WS3's {@code OrderValidator} rejects STOP,
   * STOP_LIMIT and TRAILING_STOP with {@code VALIDATION_FAILED} on {@code type} until its trigger evaluator lands (M2),
   * so the published matrix lists only these and a bot never plans around an order type it cannot place. Add the
   * other types here in the PR that enables them (and update {@code docs/rules.md}).
   */
  public static final Set<OrderType> AVAILABLE_ORDER_TYPES = Collections.unmodifiableSet(
      EnumSet.of(OrderType.MARKET, OrderType.LIMIT));

  /** The published matrix: the rows of {@link #matrix} whose type is {@linkplain #AVAILABLE_ORDER_TYPES available}. */
  static List<OrderMatrixRowDto> availableMatrix(RulesConfig.Rules r) {
    return matrix(r).stream().filter(row -> AVAILABLE_ORDER_TYPES.contains(row.type())).toList();
  }

  /** Every Phase 1 order type (§11.1), whether or not this release accepts it yet. */
  static List<OrderMatrixRowDto> matrix(RulesConfig.Rules r) {
    List<String> wholeOnly = List.of("SHARES");
    return List.of(
        new OrderMatrixRowDto(OrderType.MARKET, "REGULAR (entered outside it, queues for the next 09:30 ET open)",
            List.of(TimeInForce.DAY), List.of("SHARES", "FRACTIONAL_SHARES", "NOTIONAL"),
            "Fills at the touch within a " + plain(r.marketCollarPct()) + "% collar. Buy holds: "
                + plain(r.holdPctRegular()) + "% of the ask in REGULAR, " + plain(r.holdPctQueued())
                + "% of the reference price when queued. A notional buy is all-in: principal + fees ≤ notional."),
        new OrderMatrixRowDto(OrderType.LIMIT, "REGULAR; also PRE and POST when extendedHours=true",
            List.of(TimeInForce.DAY, TimeInForce.GTC),
            r.fractional().allowLimit() ? List.of("SHARES", "FRACTIONAL_SHARES") : wholeOnly,
            "Marketable on arrival: fills at the touch (price improvement). Resting: fills at the limit. GTC lasts "
                + r.gtcDays() + " days."),
        new OrderMatrixRowDto(OrderType.STOP, "REGULAR only", List.of(TimeInForce.DAY, TimeInForce.GTC), wholeOnly,
            "Triggers on the last eligible consolidated trade, then becomes a market order with the collar."),
        new OrderMatrixRowDto(OrderType.STOP_LIMIT, "REGULAR only", List.of(TimeInForce.DAY, TimeInForce.GTC),
            wholeOnly, "Triggers like STOP, then becomes a limit order."),
        new OrderMatrixRowDto(OrderType.TRAILING_STOP, "REGULAR only, and can only be entered during REGULAR",
            List.of(TimeInForce.DAY), wholeOnly,
            "Exactly one of trailAmount ($) or trailPercent (0 < p < 100)."));
  }

  static String latency(RulesConfig.Engine e) {
    return "Every order waits " + e.latencyBase().toMillis() + " ms + " + e.latencyJitterMin().toMillis() + "–"
        + e.latencyJitterMax().toMillis() + " ms of seeded jitter before it can fill, and only quotes confirmed after "
        + "that instant count (no look-ahead). Humans and bots get identical treatment.";
  }

  static List<String> qualityLadder() {
    return List.of(
        "REALTIME_CONSOLIDATED: two-sided consolidated quote (SIP NBBO on the paid feed, or SIM).",
        "REALTIME_SINGLE_VENUE: IEX top of book, two-sided, not crossed, and no wider than the symbol's liquidity "
            + "tier allows (10/40/150 bps for MEGA/MID/SMALL).",
        "SYNTHETIC: built around the freshest eligible consolidated print (≤ 10 s in REGULAR, ≤ 60 s in PRE/POST) "
            + "with a tier half-spread; size 100.",
        "Otherwise the order waits (AWAITING_QUOTE): market orders never fill on stale data.");
  }

  static Map<String, Object> freshness(RulesConfig.Rules r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("regularConfirmed", r.freshness().regularConfirmed().toString());
    m.put("extConfirmed", r.freshness().extConfirmed().toString());
    m.put("regularExchangeAge", r.freshness().regularExchangeAge().toString());
    m.put("extExchangeAge", r.freshness().extExchangeAge().toString());
    m.put("consolidatedPrintRegular", r.freshness().consolidatedPrintRegular().toString());
    m.put("consolidatedPrintExt", r.freshness().consolidatedPrintExt().toString());
    m.put("extHoursRequireRealQuotes", r.extHours().requireRealQuotes());
    m.put("restingRequiresPrint", r.fill().restingRequiresPrint());
    return m;
  }

  // ------------------------------------------------------------------------------------------- fees

  /** §13: rows in force today and rows scheduled later, by fee code then start date. */
  List<RulesFeeDto> fees(LocalDate today) {
    return jdbc.sql("""
            SELECT fee_code, applies_to, basis, rate, rounding, max_per_order, effective_from, effective_to,
                   source_note
              FROM fee_schedule
             WHERE effective_to IS NULL OR effective_to >= ?
             ORDER BY fee_code, effective_from""")
        .param(today)
        .query((rs, n) -> new RulesFeeDto(FeeCode.valueOf(rs.getString("fee_code")),
            RulesDto.FeeAppliesTo.valueOf(rs.getString("applies_to")),
            RulesDto.FeeBasis.valueOf(rs.getString("basis")), rs.getBigDecimal("rate"), rs.getString("rounding"),
            rs.getBigDecimal("max_per_order"), Jdbc.date(rs, "effective_from"), Jdbc.date(rs, "effective_to"),
            rs.getString("source_note")))
        .list();
  }

  // ------------------------------------------------------------------------------------------- limits, data

  private Map<String, Object> rateLimits() {
    StocksProperties.RateLimit rl = properties.ratelimit();
    Map<String, Object> reads = new LinkedHashMap<>();
    reads.put("session", rl.webReadsPerMin());
    reads.put("apiKeyOrMcp", rl.keyReadsPerMin());
    reads.put("apiKeyOrMcpBurst", rl.keyReadsBurst());
    Map<String, Object> heavy = new LinkedHashMap<>();
    heavy.put("session", rl.heavyReadsPerMinWeb());
    heavy.put("apiKeyOrMcp", rl.heavyReadsPerMinKey());
    Map<String, Object> stomp = new LinkedHashMap<>();
    stomp.put("sessionsPerPlayer", 5);
    stomp.put("marketSubscriptionsPerSession", 60);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("scope", "Per player, shared across all of a player's keys and channels (web, REST, MCP).");
    m.put("readsPerMinute", reads);
    m.put("heavyReadsPerMinute", heavy);
    m.put("heavyReads", "bars, portfolio history, leaderboard, player profiles");
    m.put("orderWritesPerMinute", rl.writesPerMin());
    m.put("orderWritesPerDay", rl.writesPerDay());
    m.put("orderWrites", "place, replace, cancel, cancel-all, close position; an idempotent replay and a preview "
        + "are free");
    m.put("openOrdersPerAccount", RulesConfig.from(environment).rules().maxOpenOrders());
    m.put("realtime", stomp);
    m.put("onLimit", "HTTP 429 RATE_LIMITED with Retry-After; MCP tools return retry_after_s");
    return m;
  }

  static String liveIexWindow(RulesConfig config) {
    if (isSimulated(config) || "sip".equalsIgnoreCase(config.alpacaFeed())
        || "alpaca-sip".equalsIgnoreCase(config.marketDataProfile())) {
      return null;
    }
    return "08:00–17:00";
  }

  static List<String> dataSources(RulesConfig config) {
    if (isSimulated(config)) {
      return List.of("SIM: simulated prices (deterministic, not real quotes). The app shows a SIM badge.");
    }
    if ("alpaca-sip".equalsIgnoreCase(config.marketDataProfile()) || "sip".equalsIgnoreCase(config.alpacaFeed())) {
      return List.of("Alpaca: real-time SIP consolidated quotes, trades and bars; calendar, assets, news and "
              + "corporate actions.",
          "Finnhub: fundamentals and ETF types.");
    }
    return List.of(
        "Alpaca Basic: real-time IEX quotes and trades (a single venue, about 2-3% of volume; live 08:00-17:00 ET), "
            + "SIP bars at least 15 minutes old, calendar, assets, news and corporate actions.",
        "Finnhub: real-time consolidated last trades (used for marks, stop triggers and trade-throughs), "
            + "fundamentals and ETF types.",
        "Quotes show their quality: REALTIME_SINGLE_VENUE (IEX), SYNTHETIC (built from the last consolidated "
            + "trade, shown as Est.), STALE.");
  }

  private static boolean isSimulated(RulesConfig config) {
    String p = config.marketDataProfile() == null ? "" : config.marketDataProfile().toLowerCase(Locale.ROOT);
    return p.isEmpty() || p.startsWith("sim");
  }

  static List<String> fairness() {
    return List.of(
        "Same rules for all: the same starting cash, market data, latency, fill rules, fees and write limits on every "
            + "channel, for humans and bots alike. Players never trade with each other.",
        "Deposits are public: net contributions and flows are shown on every leaderboard row, and admin deposits "
            + "never move anyone's return. Resets are counted publicly, and a reset never erases a loss.",
        "No live copying: other players' trades appear after the feed delay (labeled \"via API\" when an API key or "
            + "MCP placed them); holdings are shown only as previous-close weights; working orders are never visible.",
        "Rationales: AI rationales become public with the delayed trade; a human's note stays private unless they "
            + "tick \"Share note\".",
        "Kill switch: the admin can freeze any player, and every such action is audited. Bots can never act as "
            + "admin.");
  }

  // ------------------------------------------------------------------------------------------- settings

  private BigDecimal startingCash() {
    return setting(settings::defaultStartingCash, () -> properties.bootstrap().defaultStartingCash());
  }

  private boolean botsRequireRationale() {
    return setting(settings::botsRequireRationale, () -> true);
  }

  private int minFills() {
    return setting(settings::eligibilityMinFills, () -> 5);
  }

  private int minSymbols() {
    return setting(settings::eligibilityMinSymbols, () -> 2);
  }

  private Duration tradeFeedDelay() {
    return setting(settings::tradeFeedDelay, () -> Duration.ofMinutes(15));
  }

  private HoldingsVisibility holdingsVisibility() {
    return setting(settings::holdingsVisibility, () -> HoldingsVisibility.EOD_WEIGHTS);
  }

  /** A runtime setting, or the §23.4 default while {@code SettingsService} is still a Day-0 stub. */
  private static <T> T setting(Supplier<T> live, Supplier<T> fallback) {
    try {
      T value = live.get();
      return value == null ? fallback.get() : value;
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        return fallback.get();
      }
      throw e;
    }
  }

  // ------------------------------------------------------------------------------------------- version

  private String version(RulesDto content) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(jsonMapper.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
      return "1." + HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String plain(BigDecimal d) {
    return d.stripTrailingZeros().toPlainString();
  }
}
