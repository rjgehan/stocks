package com.stocks.contract.rules.dto;

import com.stocks.common.domain.AccountType;
import com.stocks.common.domain.FeeCode;
import com.stocks.common.domain.OrderGroupType;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.TimeInForce;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * §18.3 {@code Rules} ({@code GET /rules}, MCP {@code get_rules}; producer WS5's {@code RulesService}). Bots read this
 * first. Literal-typed members of the TypeScript ({@code settlement: "T+1"}, {@code shortSelling: false}, …) are
 * plain strings and booleans here; the service fills the fixed values.
 */
public record RulesDto(String version, Instant asOf, RulesAccountDto account, RulesSessionsDto sessions,
                       RulesOrdersDto orders, RulesExecutionDto execution, List<RulesFeeDto> fees,
                       Map<String, Object> rateLimits, RulesLeaderboardDto leaderboard, RulesSocialDto social,
                       List<String> dataSources, List<String> fairness, RulesCryptoDto crypto) {

  /** The rulebook without its crypto section (fixtures). */
  public RulesDto(String version, Instant asOf, RulesAccountDto account, RulesSessionsDto sessions,
                  RulesOrdersDto orders, RulesExecutionDto execution, List<RulesFeeDto> fees,
                  Map<String, Object> rateLimits, RulesLeaderboardDto leaderboard, RulesSocialDto social,
                  List<String> dataSources, List<String> fairness) {
    this(version, asOf, account, sessions, orders, execution, fees, rateLimits, leaderboard, social, dataSources,
        fairness, null);
  }

  /**
   * Crypto pairs (§29): {@code enabled} is the admin setting {@code crypto.enabled}; {@code pairs} the tradable
   * symbols. The other members describe how crypto differs from stocks, in words a bot can quote.
   */
  public record RulesCryptoDto(boolean enabled, List<String> pairs, String hours, String symbols, String quantities,
                               String orders, String settlement, String fees, String pnlDays) {}

  /** {@code type} is always {@code CASH}; {@code settlement} {@code "T+1"}. */
  public record RulesAccountDto(AccountType type, BigDecimal startingCash, String settlement, boolean pdtEnforced,
                                boolean shortSelling, String buyingPowerFormula, boolean depositsSettleImmediately,
                                RulesGfvDto gfv) {}

  /** {@code expiresAfter}: e.g. {@code "P90D"} or a description. */
  public record RulesGfvDto(boolean requiresAck, int settledCashOnlyAt, int liquidationOnlyAt, int restrictionDays,
                            String expiresAfter) {}

  /** {@code timezone} is {@code America/New_York}; times are ET ranges such as {@code "04:00–09:30"}. */
  public record RulesSessionsDto(String timezone, String pre, String regular, String post, String earlyClose,
                                 String liveIexWindow, boolean overnight) {}

  /**
   * Phase 2: {@code groups} (§11.9, the group types accepted), {@code cancelLatency} (§11.10; null when off, the
   * default) and {@code dividendAdjustment} (§14 FINRA 5330).
   */
  public record RulesOrdersDto(List<OrderMatrixRowDto> matrix, int gtcDays, int maxOpenOrders, BigDecimal maxQty,
                               String tick, BigDecimal collarPct, BigDecimal holdPctRegular,
                               BigDecimal holdPctQueued, BigDecimal fatFingerWarnPct, BigDecimal priceTooFarPct,
                               String clientOrderIdPattern, boolean rationaleRequiredForAi,
                               RulesFractionalDto fractional, List<OrderGroupRuleDto> groups, String cancelLatency,
                               String dividendAdjustment) {}

  /** One group order type (§11.9): its legs, the entry types it accepts, its time in force and notes. */
  public record OrderGroupRuleDto(OrderGroupType type, String legs, List<OrderType> entryTypes,
                                  List<TimeInForce> timeInForce, String notes) {}

  /** One row of the §11.1 order matrix. */
  public record OrderMatrixRowDto(OrderType type, String sessions, List<TimeInForce> timeInForce,
                                  List<String> qtyForms, String notes) {}

  public record RulesFractionalDto(BigDecimal minBuyNotional, BigDecimal minSellNotional, BigDecimal minQty,
                                   boolean marketOnly, boolean dayOnly, boolean rthOnly, boolean replaceable) {}

  public record RulesExecutionDto(String latency, List<String> qualityLadder, Map<String, Object> freshness,
                                  String partialFills, boolean liquiditySharedAcrossPlayers) {}

  /** One {@code fee_schedule} row (§13). */
  public record RulesFeeDto(FeeCode code, FeeAppliesTo appliesTo, FeeBasis basis, BigDecimal rate, String rounding,
                            BigDecimal maxPerOrder, LocalDate effectiveFrom, LocalDate effectiveTo, String note) {}

  public enum FeeAppliesTo { SELL, BOTH }

  public enum FeeBasis { PRINCIPAL, SHARES }

  /** {@code benchmark} is {@code "SPY"}; {@code resetPolicy}: a reset never erases a loss (§16.3). */
  public record RulesLeaderboardDto(String returnDefinition, String pnlDefinition, String pnlDayEnds, int minFills,
                                    int minSymbols, String ties, String benchmark, String resetPolicy) {}

  /**
   * {@code tradeFeedDelay}: ISO-8601 duration ({@code "PT0S"} by default). {@code visibility} is always
   * {@code "PUBLIC"}: every player's portfolio, orders, fills, activity, rationales and journal are visible to all
   * players live (§16.7).
   */
  public record RulesSocialDto(String tradeFeedDelay, String visibility) {}
}
