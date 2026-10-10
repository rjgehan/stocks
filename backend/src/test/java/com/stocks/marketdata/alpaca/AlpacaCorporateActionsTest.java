package com.stocks.marketdata.alpaca;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.marketdata.config.MarketDataProperties;
import com.stocks.marketdata.core.ProviderHttp;
import com.stocks.marketdata.core.ProviderRateLimiter;
import com.stocks.marketdata.core.WebSocketStream;
import com.stocks.marketdata.testing.FakeHttpServer;
import com.stocks.marketdata.testing.Providers;
import com.stocks.testkit.TestTimes;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /v1/corporate-actions} (§14, §15.6) against a local HTTP fake serving recorded fixtures shaped like the
 * reference at docs.alpaca.markets (checked 2026-10-10): the query, {@code next_page_token} paging, and the mapping
 * of every category to the Phase 1 types.
 */
class AlpacaCorporateActionsTest {

  FakeHttpServer http;
  AlpacaMarketDataProvider alpaca;

  @BeforeEach
  void setUp() {
    http = new FakeHttpServer();
    MutableTimeSource time = TestTimes.clock();
    MarketDataProperties.Alpaca a = Providers.alpaca(http.url(), "ws://127.0.0.1:1/v2/iex");
    ProviderHttp ph = new ProviderHttp("alpaca", a.connectTimeout(), a.readTimeout(),
        new ProviderRateLimiter("alpaca", 180, 0), time::now,
        Map.of("APCA-API-KEY-ID", a.keyId(), "APCA-API-SECRET-KEY", a.secretKey()));
    alpaca = new AlpacaMarketDataProvider(Providers.hybrid(a, null), new AlpacaRestClient(a, ph, time::now), time,
        WebSocketStream.Timings.defaults());
  }

  @AfterEach
  void tearDown() {
    http.close();
  }

  @Test
  void followsPagesAndMapsEveryCategory() {
    http.route("/v1/corporate-actions", r -> FakeHttpServer.Response.json(FakeHttpServer.fixture(
        r.query().containsKey("page_token") ? "alpaca/corporate-actions-page2.json"
            : "alpaca/corporate-actions-page1.json")));
    List<CorporateActionData> all = alpaca.corporateActions(Set.of("NVDA", "AAPL", "FB"), LocalDate.of(2026, 10, 2),
        LocalDate.of(2027, 1, 7));

    List<FakeHttpServer.Request> reqs = http.requests("/v1/corporate-actions");
    assertThat(reqs).hasSize(2);
    assertThat(reqs.getFirst().query()).containsEntry("symbols", "AAPL,FB,NVDA")
        .containsEntry("start", "2026-10-02").containsEntry("end", "2027-01-07").containsEntry("limit", "1000")
        .doesNotContainKey("page_token");
    assertThat(reqs.get(1).query()).containsEntry("page_token", "cGFnZTI=");
    assertThat(reqs.getFirst().headers()).containsEntry("apca-api-key-id", "test-key");

    CorporateActionData nvda = one(all, d -> d.symbol().equals("NVDA"));
    assertThat(nvda.type()).isEqualTo(CorporateActionType.FORWARD_SPLIT);
    assertThat(nvda.providerRef()).isEqualTo("alpaca:4a2f2f3e-0b7a-4c1d-9a1e-1d2b3c4d5e01");
    assertThat(nvda.exDate()).isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(nvda.oldRate()).isEqualByComparingTo("1");
    assertThat(nvda.newRate()).isEqualByComparingTo("10");

    CorporateActionData sqqq = one(all, d -> d.symbol().equals("SQQQ"));
    assertThat(sqqq.type()).isEqualTo(CorporateActionType.REVERSE_SPLIT);
    assertThat(sqqq.oldRate()).isEqualByComparingTo("5");
    assertThat(sqqq.newRate()).isEqualByComparingTo("1");

    // a reverse split with a new symbol: the rename first, then the split on the new symbol (§14 order)
    CorporateActionData rename = one(all, d -> d.symbol().equals("OLDR"));
    assertThat(rename.type()).isEqualTo(CorporateActionType.NAME_CHANGE);
    assertThat(rename.newSymbol()).isEqualTo("NEWR");
    assertThat(rename.providerRef()).endsWith(":name");
    CorporateActionData newr = one(all, d -> d.symbol().equals("NEWR"));
    assertThat(newr.type()).isEqualTo(CorporateActionType.REVERSE_SPLIT);
    assertThat(newr.exDate()).isEqualTo(LocalDate.of(2026, 10, 15));

    CorporateActionData div = one(all, d -> d.symbol().equals("AAPL"));
    assertThat(div.type()).isEqualTo(CorporateActionType.CASH_DIVIDEND);
    assertThat(div.cashRate()).isEqualByComparingTo("0.26");
    assertThat(div.exDate()).isEqualTo(LocalDate.of(2026, 11, 10));
    assertThat(div.payableDate()).isEqualTo(LocalDate.of(2026, 11, 13));

    CorporateActionData fb = one(all, d -> d.symbol().equals("FB"));
    assertThat(fb.type()).isEqualTo(CorporateActionType.NAME_CHANGE);
    assertThat(fb.newSymbol()).isEqualTo("META");
    assertThat(fb.exDate()).isEqualTo(LocalDate.of(2026, 10, 13));       // process_date

    CorporateActionData stock = one(all, d -> d.symbol().equals("STKD"));
    assertThat(stock.type()).isEqualTo(CorporateActionType.FORWARD_SPLIT);  // 5% stock dividend = 1 → 1.05
    assertThat(stock.oldRate()).isEqualByComparingTo("1");
    assertThat(stock.newRate()).isEqualByComparingTo("1.05");

    assertThat(one(all, d -> d.symbol().equals("TGTC")).type()).isEqualTo(CorporateActionType.CASH_MERGER);
    assertThat(one(all, d -> d.symbol().equals("TGTC")).exDate()).isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(one(all, d -> d.symbol().equals("SMLC")).type()).isEqualTo(CorporateActionType.STOCK_MERGER);
    assertThat(one(all, d -> d.symbol().equals("PRNT")).type()).isEqualTo(CorporateActionType.SPIN_OFF);
    assertThat(one(all, d -> d.symbol().equals("GONE")).type()).isEqualTo(CorporateActionType.DELISTING);
    assertThat(all).hasSize(11);
  }

  @Test
  void anEmptyAnswerIsNoActions() {
    http.json("/v1/corporate-actions", "{\"corporate_actions\":{},\"next_page_token\":null}");
    assertThat(alpaca.corporateActions(Set.of(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))).isEmpty();
    assertThat(http.requests("/v1/corporate-actions").getFirst().query()).doesNotContainKey("symbols");
  }

  private static CorporateActionData one(List<CorporateActionData> all, Predicate<CorporateActionData> p) {
    List<CorporateActionData> m = all.stream().filter(p).toList();
    assertThat(m).hasSize(1);
    return m.getFirst();
  }
}
