package com.stocks.trading.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderType;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.security.Scopes;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.contract.trading.dto.PlaceOrderRequest;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.common.domain.AuthMethod;
import com.stocks.common.domain.Channel;
import com.stocks.common.domain.PlayerRole;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.ScriptedMarketData;
import com.stocks.testkit.TestTimes;
import com.stocks.trading.engine.InvariantChecker;
import com.stocks.trading.engine.testkit.DirectEngineExecutor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the WS3 Spring tests: one shared context (H2, or embedded PostgreSQL under {@code -Ppg-it}) with the
 * engine driven on the calling thread, the scripted market and the test calendar. Every test makes its own players
 * and symbols, so tests never see each other's orders; invariants I1–I9 are asserted after each test (§26).
 */
@SpringBootTest(properties = "stocks.engine.auto-start=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TradingTestConfig.class)
public abstract class TradingTestBase {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired protected MutableTimeSource time;
  @Autowired protected ScriptedMarketData md;
  @Autowired protected DirectEngineExecutor engine;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected OrderService orders;
  @Autowired protected OrderQueries orderQueries;
  @Autowired protected AccountService accountService;
  @Autowired protected AccountQueries accountQueries;
  @Autowired protected CashTransferService transfers;
  @Autowired protected InvariantChecker invariants;
  @Autowired protected MockMvc mvc;

  protected Fixtures fx;

  /** A player with an ACTIVE funded account. */
  public record Player(Fixtures.PlayerRef ref, long accountId, PlayerPrincipal principal) {
    public long id() {
      return ref.id();
    }

    public String handle() {
      return ref.handle();
    }
  }

  @BeforeEach
  void tradingSetUp() {
    fx = new Fixtures(jdbc, time);
    time.set(TestTimes.START);
  }

  @AfterEach
  void invariantsHold() {
    InvariantResult r = invariants.check();
    assertThat(r.failures()).as("invariants I1–I9").isEmpty();
  }

  protected static int next() {
    return SEQ.incrementAndGet();
  }

  /** A fresh tradable, fractionable MEGA-tier symbol known to market data. */
  protected String symbol() {
    String s = "T" + next();
    md.withAsset(s);
    return s;
  }

  protected Player human() {
    return human("500.00");
  }

  protected Player human(String startingCash) {
    Fixtures.PlayerRef p = fx.human("p" + next());
    AccountDto acc = accountService.openInitialAccount(p.id(), new BigDecimal(startingCash), p.id());
    return new Player(p, accountQueries.activeAccountId(p.id()), session(p, PlayerKind.HUMAN));
    // acc is valued live; tests read it again when they need numbers
  }

  protected Player bot() {
    Fixtures.PlayerRef owner = fx.human("o" + next());
    Fixtures.PlayerRef p = fx.ai("b" + next(), owner);
    accountService.openInitialAccount(p.id(), new BigDecimal("500.00"), owner.id());
    String token = fx.apiKey(p, "test-pepper-not-secret-0123456789abcdef");
    long apiKeyId = jdbc.sql("SELECT id FROM api_key WHERE key_id = ?")
        .param(com.stocks.security.ApiKeys.parse(token).orElseThrow().keyId()).query(Long.class).single();
    PlayerPrincipal key = new PlayerPrincipal(p.id(), p.publicId(), p.handle(), PlayerKind.AI, PlayerRole.PLAYER,
        Scopes.ALL, AuthMethod.API_KEY, apiKeyId, null, TestTimes.START);
    return new Player(p, accountQueries.activeAccountId(p.id()), key);
  }

  protected static PlayerPrincipal session(Fixtures.PlayerRef p, PlayerKind kind) {
    return new PlayerPrincipal(p.id(), p.publicId(), p.handle(), kind, PlayerRole.PLAYER, Scopes.ALL,
        AuthMethod.SESSION, null, null, TestTimes.START);
  }

  protected static RequestMeta meta() {
    return new RequestMeta("req-test", Channel.WEB, "127.0.0.1", "junit", null, null);
  }

  /** The request meta of a bot calling REST with its API key. */
  protected static RequestMeta botMeta() {
    return new RequestMeta("req-test", Channel.REST_KEY, "127.0.0.1", "junit-bot", null, null);
  }

  /** A two-sided quote observed now. */
  protected void quote(String symbol, String bid, long bidSize, String ask, long askSize) {
    md.quote(symbol, time.now(), bid, bidSize, ask, askSize);
    md.publish(symbol);
  }

  protected void print(String symbol, String price, long size) {
    md.print(symbol, time.now(), price, size);
    md.publish(symbol);
  }

  /** Moves time and runs one engine step (dirty drain + sweep). */
  protected void after(Duration d) {
    engine.advance(d);
  }

  protected void step() {
    engine.step();
  }

  protected static PlaceOrderRequest market(String cid, String symbol, Side side, String qty) {
    return new PlaceOrderRequest(cid, symbol, side, OrderType.MARKET, new BigDecimal(qty), null, null, null, null,
        null, TimeInForce.DAY, false, "test", null, Set.of());
  }

  protected static PlaceOrderRequest notional(String cid, String symbol, Side side, String amount) {
    return new PlaceOrderRequest(cid, symbol, side, OrderType.MARKET, null, new BigDecimal(amount), null, null, null,
        null, TimeInForce.DAY, false, "test", null, Set.of());
  }

  protected static PlaceOrderRequest limit(String cid, String symbol, Side side, String qty, String price,
                                           TimeInForce tif, boolean ext) {
    return new PlaceOrderRequest(cid, symbol, side, OrderType.LIMIT, new BigDecimal(qty), null,
        new BigDecimal(price), null, null, null, tif, ext, "test", null, Set.of());
  }

  protected PlaceOrderResponse place(Player p, PlaceOrderRequest req) {
    return orders.place(p.principal(), req, meta());
  }

  protected OrderDto order(Player p, String orderId) {
    return orderQueries.byPublicId(p.accountId(), orderId, true).orElseThrow();
  }

  protected AccountDto account(Player p) {
    return accountQueries.account(p.accountId());
  }

  protected static String cid() {
    return "c-" + next();
  }

  protected static Instant et(String isoLocal) {
    return com.stocks.common.time.Et.at(java.time.LocalDate.parse(isoLocal.substring(0, 10)),
        java.time.LocalTime.parse(isoLocal.substring(11)));
  }
}
