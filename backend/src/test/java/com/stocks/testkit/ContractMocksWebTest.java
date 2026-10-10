package com.stocks.testkit;

import com.jayway.jsonpath.JsonPath;
import com.stocks.common.time.MutableTimeSource;
import com.stocks.config.StocksProperties;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.portfolio.PerformanceQueries;
import com.stocks.contract.portfolio.ValuationService;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.AdminOrderService;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.EngineControl;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.watchlist.WatchlistService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Base for WS1 web tests that exercise the real WS1 stack (security chains, controllers, services, H2 or embedded
 * PostgreSQL) while the other workstreams' contracts are Mockito mocks, so delegation can be verified before WS2–WS4
 * land. Every subclass shares one Spring context (same mocks, same imports); mocks are reset after each test and the
 * shared {@link MutableTimeSource} only moves forward.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestClockConfig.class, RecordedEvents.class})
public abstract class ContractMocksWebTest {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired protected MockMvc mvc;
  @Autowired protected JdbcClient jdbc;
  @Autowired protected MutableTimeSource time;
  @Autowired protected StocksProperties properties;

  @MockitoBean protected AccountService accountService;
  @MockitoBean protected AccountQueries accountQueries;
  @MockitoBean protected CashTransferService cashTransfers;
  @MockitoBean protected AdminOrderService adminOrders;
  @MockitoBean protected OrderQueries orderQueries;
  @MockitoBean protected EngineControl engineControl;
  @MockitoBean protected CorporateActionQueries corporateActionQueries;
  @MockitoBean protected CorporateActionService corporateActions;
  @MockitoBean protected WatchlistService watchlists;
  @MockitoBean protected ValuationService valuations;
  @MockitoBean protected PerformanceQueries performance;
  @MockitoBean protected CalendarAdmin calendarAdmin;

  protected Fixtures fx;

  @BeforeEach
  void initFixtures() {
    fx = new Fixtures(jdbc, time);
  }

  /** A unique lower-case handle with this prefix (≤ 32 characters). */
  protected static String handle(String prefix) {
    return (prefix + SEQ.incrementAndGet()).toLowerCase();
  }

  protected String pepper() {
    return properties.security().apiKeyPepper();
  }

  protected static String body(MvcResult r) {
    return WebSession.contentOf(r);
  }

  protected static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }

  protected static String code(MvcResult r) {
    return JsonPath.read(WebSession.contentOf(r), "$.code");
  }

  protected static <T> T json(MvcResult r, String path) {
    return JsonPath.read(WebSession.contentOf(r), path);
  }

  /** An admin with a fresh session (recent authentication). */
  protected WebSession adminSession(Fixtures.PlayerRef admin) {
    return WebSession.login(mvc, admin.handle(), Fixtures.PASSWORD);
  }
}
