package com.stocks.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.HistoryRange;
import com.stocks.common.domain.HoldingsVisibility;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.Timeframe;
import com.stocks.common.domain.TransferType;
import com.stocks.common.domain.WaitReason;
import com.stocks.config.JacksonConfig;
import com.stocks.contract.admin.dto.SettingsDto;
import com.stocks.contract.identity.dto.PlayerSummaryDto;
import com.stocks.contract.marketdata.dto.BarDto;
import com.stocks.contract.marketdata.dto.BarsDto;
import com.stocks.contract.marketdata.dto.QuoteDto;
import com.stocks.contract.marketdata.model.AdjustmentMode;
import com.stocks.contract.portfolio.dto.PlayerProfileDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.DepositsSummaryDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.ProfileStatDto;
import com.stocks.contract.portfolio.dto.PlayerProfileDto.ProfileStatsDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto;
import com.stocks.contract.portfolio.dto.PortfolioHistoryDto.HistorySummaryDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.AllTargetRequest;
import com.stocks.contract.trading.dto.CashTransferRequest;
import com.stocks.contract.trading.dto.EstimateDto;
import com.stocks.contract.trading.dto.FeesDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.SingleTargetRequest;
import com.stocks.contract.trading.dto.SplitDetailDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden JSON samples for representative DTOs (§6.3, §18.3, §18.4). Each golden is the exact wire shape: property
 * order is the record order (= the §18.3 TypeScript order), decimals are strings with their scale, nulls are present.
 */
class ContractGoldenJsonTest {

  static final JsonMapper MAPPER = JacksonConfig.newMapper();

  static Instant ts(String s) {
    return Instant.parse(s);
  }

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  /** The §18.4 example response (with the two detail-only members present as null, §6.3). */
  static final String PLACE_ORDER_RESPONSE_18_4 = """
      { "order": { "orderId": "ord_01J9ZC7M0Q8W5T3K2R1N6B4V8X", "clientOrderId": "claude-20261009-0007",
          "accountId": "acc_01J9Y…", "symbol": "NVDA", "side": "BUY", "type": "MARKET", "qty": null, "notional": "125.00",
          "fractional": true, "limitPrice": null, "stopPrice": null, "trailAmount": null, "trailPercent": null,
          "watermarkPrice": null, "collarPrice": null, "timeInForce": "DAY", "extendedHours": false,
          "status": "PENDING", "waitReason": "LATENCY", "statusReason": null, "triggered": false,
          "filledQty": "0", "filledPrincipal": "0.00", "avgFillPrice": null, "fees": "0.00",
          "reservedCash": "125.00", "reservedQty": "0", "sessionDate": "2026-10-09",
          "receivedAt": "2026-10-09T14:31:02.120Z", "acceptedAt": "2026-10-09T14:31:02.126Z",
          "eligibleAt": "2026-10-09T14:31:02.729Z", "activatedAt": null, "triggeredAt": null,
          "expiresAt": "2026-10-09T20:00:00.000Z", "doneAt": null, "replacesOrderId": null, "replacedByOrderId": null,
          "channel": "REST_KEY", "rationale": "Momentum after guidance raise; …",
          "executions": null, "events": null },
        "estimate": { "price": "131.2000", "qty": "0.95274", "principal": "125.00",
          "fees": { "sec": "0.00", "taf": "0.00", "cat": "0.00", "total": "0.00" }, "hold": "125.00",
          "quoteAsOf": "2026-10-09T14:31:01.904Z", "quoteQuality": "REALTIME_SINGLE_VENUE" },
        "buyingPowerBefore": "500.00", "buyingPowerAfter": "375.00", "warnings": [], "replayed": false }
      """;

  static PlaceOrderResponse example18_4() {
    OrderDto order = new OrderDto("ord_01J9ZC7M0Q8W5T3K2R1N6B4V8X", "claude-20261009-0007", "acc_01J9Y…", "NVDA",
        Side.BUY, OrderType.MARKET, null, d("125.00"), true, null, null, null, null, null, null, TimeInForce.DAY,
        false, OrderStatus.PENDING, WaitReason.LATENCY, null, false, d("0"), d("0.00"), null, d("0.00"),
        d("125.00"), d("0"), LocalDate.of(2026, 10, 9), ts("2026-10-09T14:31:02.120Z"),
        ts("2026-10-09T14:31:02.126Z"), ts("2026-10-09T14:31:02.729Z"), null, null, ts("2026-10-09T20:00:00Z"),
        null, null, null, Channel.REST_KEY, "Momentum after guidance raise; …", null, null);
    EstimateDto estimate = new EstimateDto(d("131.2000"), d("0.95274"), d("125.00"), FeesDto.zero(), d("125.00"),
        ts("2026-10-09T14:31:01.904Z"), DataQuality.REALTIME_SINGLE_VENUE);
    return new PlaceOrderResponse(order, estimate, d("500.00"), d("375.00"), List.of(), false);
  }

  @Test
  void placeOrderResponseMatchesTheSection18_4Example() throws Exception {
    assertGolden(example18_4(), PLACE_ORDER_RESPONSE_18_4);
    assertThat(MAPPER.readValue(PLACE_ORDER_RESPONSE_18_4, PlaceOrderResponse.class)).isEqualTo(example18_4());
  }

  @Test
  void placeOrderRequestFromTheSection18_4BodyAndFromNumbers() throws Exception {
    PlaceOrderRequest req = MAPPER.readValue("""
        { "clientOrderId": "claude-20261009-0007", "symbol": "NVDA", "side": "BUY", "type": "MARKET",
          "notional": "125.00", "timeInForce": "DAY", "extendedHours": false,
          "rationale": "Momentum after guidance raise; 25% of equity, keeps 75% settled cash for GFV-safe exits." }
        """, PlaceOrderRequest.class);
    assertThat(req.notional()).isEqualTo(d("125.00"));
    assertThat(req.qty()).isNull();
    assertThat(req.timeInForce()).isEqualTo(TimeInForce.DAY);
    assertThat(req.acknowledge()).isNull();

    // a bot sending numbers and lower-case enums, omitting optional members (§6.3, §18)
    PlaceOrderRequest numbers = MAPPER.readValue("""
        {"clientOrderId":"b-1","symbol":"AAPL","side":"sell","type":"limit","qty":0.1,"limitPrice":227.4,
         "acknowledge":["GFV_RISK"]}
        """, PlaceOrderRequest.class);
    assertThat(numbers.side()).isEqualTo(Side.SELL);
    assertThat(numbers.type()).isEqualTo(OrderType.LIMIT);
    assertThat(numbers.qty()).isEqualTo(d("0.1"));
    assertThat(numbers.limitPrice()).isEqualTo(d("227.4"));
    assertThat(numbers.extendedHours()).isNull();
    assertThat(numbers.extendedHoursOrDefault()).isFalse();
    assertThat(req.extendedHours()).isFalse();
    assertThat(numbers.timeInForce()).isNull();
    assertThat(numbers.acknowledge()).containsExactly("GFV_RISK");
  }

  @Test
  void unknownSymbolQuoteHasQualityNoneAndEveryMemberPresent() throws Exception {
    QuoteDto q = new QuoteDto("ZZZZ", null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, false, null, null, null, DataQuality.NONE, "none", false, SessionType.REGULAR);
    assertGolden(q, """
        {"symbol":"ZZZZ","bid":null,"bidSize":null,"ask":null,"askSize":null,"last":null,"lastSize":null,
         "lastAt":null,"prevClose":null,"change":null,"changePct":null,"dayOpen":null,"dayHigh":null,"dayLow":null,
         "volume":null,"volumeIsPartial":false,"extended":null,"asOf":null,"ageMs":null,"quality":"NONE",
         "source":"none","halted":false,"session":"REGULAR"}
        """);
  }

  @Test
  void barsUseWireValuesAndIntegerVolume() throws Exception {
    BarsDto bars = new BarsDto("AAPL", Timeframe.MIN1, AdjustmentMode.SPLIT,
        List.of(new BarDto(ts("2026-10-09T13:30:00Z"), d("227.4000"), d("228.0100"), d("227.1000"), d("227.9000"),
            120_000L, null)), null, "simulated");
    assertGolden(bars, """
        {"symbol":"AAPL","timeframe":"1Min","adjustment":"SPLIT",
         "bars":[{"t":"2026-10-09T13:30:00.000Z","o":"227.4000","h":"228.0100","l":"227.1000","c":"227.9000",
                  "v":120000,"vw":null}],
         "tailFrom":null,"source":"simulated"}
        """);
    assertThat(MAPPER.readValue("{\"symbol\":\"AAPL\",\"timeframe\":\"1min\",\"adjustment\":\"split\",\"bars\":[],"
        + "\"tailFrom\":null,\"source\":\"x\"}", BarsDto.class).timeframe()).isEqualTo(Timeframe.MIN1);
  }

  @Test
  void activityDetailIsATaggedUnion() throws Exception {
    ActivityDto split = new ActivityDto("aev_01J9Z", ts("2026-10-12T07:45:00Z"), ActivityType.SPLIT, "NVDA", null,
        d("3.00000"), null, null, null, null, LocalDate.of(2026, 10, 12), null, "3-for-2 split", ActorKind.SYSTEM,
        null, null, null,
        new SplitDetailDto("cac_01J9Z", d("2.000000000000"), d("3.000000000000"), d("2.00000"), d("3.00000"),
            d("250.00"), d("0.00"), d("0.00000"), d("0.00")));
    assertGolden(split, """
        {"id":"aev_01J9Z","at":"2026-10-12T07:45:00.000Z","type":"SPLIT","symbol":"NVDA","side":null,"qty":"3.00000",
         "price":null,"fees":null,"amount":null,"balanceAfter":null,"tradeDate":"2026-10-12","settleDate":null,
         "memo":"3-for-2 split","by":"SYSTEM","orderId":null,"executionId":null,"transferId":null,
         "detail":{"type":"SPLIT","corporateActionId":"cac_01J9Z","oldRate":"2.000000000000",
                   "newRate":"3.000000000000","qtyBefore":"2.00000","qtyAfter":"3.00000","costBasis":"250.00",
                   "cashInLieu":"0.00","dustQty":"0.00000","realizedPnl":"0.00"}}
        """);
    assertThat(MAPPER.readValue(MAPPER.writeValueAsString(split), ActivityDto.class)).isEqualTo(split);
  }

  @Test
  void cashTransferRequestTargetsBindFromTheTypeScriptShape() throws Exception {
    CashTransferRequest all = MAPPER.readValue("""
        {"target":{"scope":"ALL"},"type":"DEPOSIT","amount":"100.00","memo":"Halloween bonus"}
        """, CashTransferRequest.class);
    assertThat(all.target()).isEqualTo(new AllTargetRequest(null));
    assertThat(all.type()).isEqualTo(TransferType.DEPOSIT);
    assertThat(all.amount()).isEqualTo(d("100.00"));
    assertThat(all.allOrNothing()).isNull();

    CashTransferRequest single = MAPPER.readValue("""
        {"target":{"scope":"SINGLE","handle":"alice"},"type":"ADJUSTMENT","amount":-5,
         "treatAs":"PNL_CORRECTION","memo":"fix","allOrNothing":true}
        """, CashTransferRequest.class);
    assertThat(single.target()).isEqualTo(new SingleTargetRequest("alice"));
    assertThat(single.amount()).isEqualTo(d("-5"));
    assertThat(single.treatAs()).isEqualTo(AdjustmentTreatment.PNL_CORRECTION);

    CashTransferRequest kinds = new CashTransferRequest(new AllTargetRequest(java.util.Set.of(PlayerKind.AI)),
        TransferType.DEPOSIT, d("250.00"), null, "bots only", false, false);
    assertGolden(kinds, """
        {"target":{"scope":"ALL","kinds":["AI"]},"type":"DEPOSIT","amount":"250.00","treatAs":null,
         "memo":"bots only","allOrNothing":false,"acceptDegradedMarks":false}
        """);
  }

  @Test
  void settingsUseTheAppSettingKeys() throws Exception {
    SettingsDto s = new SettingsDto(d("500.00"), false, LocalDate.of(2026, 10, 1), 5, 2, "PT15M",
        HoldingsVisibility.EOD_WEIGHTS, true, true, false);
    assertGolden(s, """
        {"league.defaultStartingCash":"500.00","league.allowSelfReset":false,"league.seasonStart":"2026-10-01",
         "league.eligibility.minFills":5,"league.eligibility.minSymbols":2,"social.tradeFeedDelay":"PT15M",
         "social.holdingsVisibility":"EOD_WEIGHTS","social.showDepositsInFeed":true,"bots.requireRationale":true,
         "league.humanKindExcludesApiTraders":false}
        """);
  }

  @Test
  void returnMembersAndProfileStatKeys() throws Exception {
    ProfileStatDto zero = new ProfileStatDto(d("0.0000000000"), d("0.00"), null);
    ProfileStatDto week = new ProfileStatDto(d("0.1622641509"), d("100.00"), 1);
    PlayerProfileDto p = new PlayerProfileDto(
        new PlayerSummaryDto("ply_01J9", "claude", "Claude", PlayerKind.AI, "claude-opus", null,
            ts("2026-10-01T12:00:00Z"), 0),
        "admin", 1, new ProfileStatsDto(zero, week, zero, zero, zero), null, d("1000.00"),
        new DepositsSummaryDto(1, d("500.00")), 6, null, null, null, List.of(), List.of());
    assertGolden(p, """
        {"player":{"playerId":"ply_01J9","handle":"claude","displayName":"Claude","kind":"AI","aiModel":"claude-opus",
                   "avatarSeed":null,"memberSince":"2026-10-01T12:00:00.000Z","resets":0},
         "ownerHandle":"admin","runNo":1,
         "stats":{"TODAY":{"return":"0.0000000000","pnl":"0.00","rank":null},
                  "WEEK":{"return":"0.1622641509","pnl":"100.00","rank":1},
                  "MONTH":{"return":"0.0000000000","pnl":"0.00","rank":null},
                  "YTD":{"return":"0.0000000000","pnl":"0.00","rank":null},
                  "ALL":{"return":"0.0000000000","pnl":"0.00","rank":null}},
         "equity":null,"netContributions":"1000.00","deposits":{"count":1,"total":"500.00"},"fills":6,
         "winRate":null,"moneyWeightedReturnAll":null,"holdingsAsOf":null,"holdings":[],"recentTrades":[]}
        """);
    PortfolioHistoryDto h = new PortfolioHistoryDto(HistoryRange.W1, List.of(), List.of(),
        new HistorySummaryDto(d("0.1622641509"), d("100.00"), null, null));
    assertGolden(h, """
        {"range":"1W","points":[],"flows":[],
         "summary":{"return":"0.1622641509","pnl":"100.00","excessVsBenchmark":null,"maxDrawdown":null}}
        """);
  }

  @Test
  void accountRestrictionEnumsAreUpperSnake() throws Exception {
    assertThat(MAPPER.writeValueAsString(AccountRestriction.SETTLED_CASH_ONLY)).isEqualTo("\"SETTLED_CASH_ONLY\"");
  }

  /** Same JSON tree AND the same property order (record order = §18.3 order). */
  static void assertGolden(Object value, String golden) throws Exception {
    JsonNode actual = MAPPER.readTree(MAPPER.writeValueAsString(value));
    JsonNode expected = MAPPER.readTree(golden);
    assertThat(actual).isEqualTo(expected);
    assertSameOrder(actual, expected, "$");
  }

  private static void assertSameOrder(JsonNode a, JsonNode e, String path) {
    if (e.isObject()) {
      assertThat(List.copyOf(a.propertyNames())).as("property order at " + path)
          .containsExactlyElementsOf(List.copyOf(e.propertyNames()));
      for (String name : e.propertyNames()) {
        assertSameOrder(a.get(name), e.get(name), path + "." + name);
      }
    } else if (e.isArray()) {
      for (int i = 0; i < e.size(); i++) {
        assertSameOrder(a.get(i), e.get(i), path + "[" + i + "]");
      }
    }
  }
}
