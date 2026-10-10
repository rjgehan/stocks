package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.Timeframe;
import com.stocks.contract.marketdata.model.Bar;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * I10 (§8.4, §25): executions are checked against their minute's SIP 1-minute bar widened by the reference spread,
 * only after the 16-minute embargo and while they are at most 30 minutes old; a finding is a warning (log, audit row
 * once), never a halt.
 */
class FillQualityAuditTest extends TradingTestBase {

  @Autowired FillQualityAudit audit;

  @Test
  void anExecutionOutsideItsSipMinuteIsReportedOnceAfterTheEmbargo() {
    Player p = human("2000.00");
    String inside = symbol();
    String outside = symbol();
    String noBar = symbol();
    String a = buy(p, inside, "99.98", "100.00");
    String b = buy(p, outside, "49.98", "50.00");
    String c = buy(p, noBar, "9.98", "10.00");
    Instant minute = time.now().truncatedTo(ChronoUnit.MINUTES);
    md.scriptBar(bar(inside, minute, "99.90", "100.10"));
    md.scriptBar(bar(outside, minute, "51.00", "51.50"));          // 50.00 < 51.00 − 0.02
    md.scriptBar(bar(noBar, minute.minus(Duration.ofMinutes(1)), "9.00", "11.00"));   // another minute: no bar for c

    FillQualityAudit.Pass early = audit.run(time.now().plus(Duration.ofMinutes(10)));
    assertThat(early.findings()).extracting(FillQualityAudit.Finding::executionId).doesNotContain(a, b, c);
    assertThat(warnings(b)).as("still under the embargo").isZero();

    FillQualityAudit.Pass pass = audit.run(time.now().plus(Duration.ofMinutes(17)));
    assertThat(pass.findings()).extracting(FillQualityAudit.Finding::executionId).contains(b).doesNotContain(a, c);
    assertThat(pass.findings()).filteredOn(f -> f.executionId().equals(b)).singleElement().satisfies(f -> {
      assertThat(f.price()).isEqualByComparingTo("50.00");
      assertThat(f.low()).isEqualByComparingTo("51.00");
      assertThat(f.spread()).isEqualByComparingTo("0.02");
    });
    assertThat(pass.awaitingBar()).as("c has no SIP bar yet").isPositive();
    assertThat(warnings(b)).isEqualTo(1);
    assertThat(warnings(a)).isZero();
    assertThat(audit.recent()).extracting(FillQualityAudit.Finding::executionId).contains(b);

    FillQualityAudit.Pass again = audit.run(time.now().plus(Duration.ofMinutes(18)));
    assertThat(again.findings()).extracting(FillQualityAudit.Finding::executionId).doesNotContain(b);
    assertThat(warnings(b)).as("reported once").isEqualTo(1);
    assertThat(engine.status().state()).as("never halts").isEqualTo(com.stocks.contract.trading.EngineState.RUNNING);
  }

  @Test
  void theRangeIsWidenedByTheReferenceSpreadOrOneTick() {
    Instant t = Instant.parse("2026-10-09T14:00:01Z");
    Bar bar = bar("X", t.truncatedTo(ChronoUnit.MINUTES), "100.00", "101.00");
    assertThat(FillQualityAudit.check("e", "X", t, d("99.95"), d("99.90"), d("100.00"), bar)).as("within 0.10")
        .isNull();
    assertThat(FillQualityAudit.check("e", "X", t, d("101.10"), d("101.00"), d("101.10"), bar)).isNull();
    assertThat(FillQualityAudit.check("e", "X", t, d("101.12"), d("101.00"), d("101.10"), bar)).isNotNull();
    assertThat(FillQualityAudit.check("e", "X", t, d("99.99"), null, null, bar)).as("one tick without a quote")
        .isNull();
    assertThat(FillQualityAudit.check("e", "X", t, d("99.98"), null, null, bar)).isNotNull();
  }

  private String buy(Player p, String s, String bid, String ask) {
    quote(s, bid, 1000, ask, 1000);
    OrderDto o = place(p, market(cid(), s, Side.BUY, "1")).order();
    time.advance(Duration.ofSeconds(1));
    quote(s, bid, 1000, ask, 1000);
    step();
    OrderDto f = order(p, o.orderId());
    assertThat(f.status()).isEqualTo(OrderStatus.FILLED);
    return f.executions().get(0).executionId();
  }

  private int warnings(String executionId) {
    return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE category = 'SYSTEM' AND action = ? AND target_id = ?")
        .params(FillQualityAudit.ACTION, executionId).query(Integer.class).single();
  }

  private static Bar bar(String symbol, Instant start, String low, String high) {
    return new Bar(symbol, Timeframe.MIN1, start, d(low), d(high), d(low), d(high), 1000, null, 10, false);
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }
}
