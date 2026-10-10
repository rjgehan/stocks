package com.stocks.trading.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CancelReason;
import com.stocks.common.domain.OrderEventType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.contract.calendar.MarketCalendar;
import com.stocks.contract.calendar.SessionChanged;
import com.stocks.contract.jobs.CatchUpPolicy;
import com.stocks.contract.jobs.JobOccurrence;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.trading.AdminOrderService;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.trading.session.DailyTradingJobs;
import com.stocks.trading.session.SessionTransitionJob;
import com.stocks.trading.testkit.TradingTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Engine administration (§12.9, §21.1, §9.4, §10.4): admin cancel of any player's order, an invariant failure halting
 * the engine until it is repaired and resumed, session transitions from the scheduled job, and daily maintenance
 * lifting an expired GFV restriction.
 */
@RecordApplicationEvents
class EngineAdminTest extends TradingTestBase {

  @Autowired AdminOrderService adminOrders;
  @Autowired SessionTransitionJob transitions;
  @Autowired MarketCalendar calendar;
  @Autowired ApplicationEvents events;
  @Autowired List<ScheduledJob> jobs;

  @Test
  void theAdminCancelsAnyPlayersOrder() {
    Player p = human();
    String s = symbol();
    quote(s, "9.99", 300, "10.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "1", "9.00", TimeInForce.GTC, false)).order();
    long admin = fx.admin("adm" + next()).id();
    OrderDto c = adminOrders.cancel(o.orderId(), CancelReason.ADMIN, admin, meta());
    assertThat(c.status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(c.statusReason()).isEqualTo("ADMIN");
    assertThat(order(p, o.orderId()).events()).last().satisfies(e -> {
      assertThat(e.type()).isEqualTo(OrderEventType.CANCELLED);
      assertThat(e.detail().toString()).contains("ADMIN");
    });
    assertThatThrownBy(() -> adminOrders.cancel("ord_01UNKNOWN", CancelReason.ADMIN, admin, meta()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
  }

  @Test
  void anInvariantFailureHaltsTheEngineUntilRepairedAndResumed() {
    Player p = human();
    jdbc.sql("UPDATE account SET cash_balance = cash_balance + 1 WHERE id = ?").param(p.accountId()).update();
    try {
      InvariantResult r = engine.run(new EngineCommand.CheckInvariantsCmd());
      assertThat(r.ok()).isFalse();
      assertThat(r.failures()).anyMatch(f -> f.startsWith("I2 account " + p.accountId()));
      assertThat(engine.status().state()).isEqualTo(EngineState.HALTED);
      assertThatThrownBy(() -> engine.run(new EngineCommand.SweepCmd()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ENGINE_HALTED));
      engine.run(new EngineCommand.ResumeEngineCmd(0));
      assertThat(engine.status().state()).as("resume re-checks first").isEqualTo(EngineState.HALTED);
    } finally {
      jdbc.sql("UPDATE account SET cash_balance = cash_balance - 1 WHERE id = ?").param(p.accountId()).update();
    }
    engine.run(new EngineCommand.ResumeEngineCmd(0));
    assertThat(engine.status().state()).isEqualTo(EngineState.RUNNING);
    assertThat(engine.status().haltReason()).isNull();
  }

  @Test
  void theSessionTransitionJobPausesAndResumesOpenOrders() {
    Player p = human();
    String s = symbol();
    quote(s, "99.98", 300, "100.00", 300);
    OrderDto o = place(p, limit(cid(), s, Side.BUY, "1", "95.00", TimeInForce.GTC, false)).order();
    after(Duration.ofSeconds(1));
    List<JobOccurrence> occ = transitions.occurrencesBetween(et("2026-10-09T10:00:00"), et("2026-10-12T10:00:00"),
        calendar);
    assertThat(occ).extracting(JobOccurrence::runKey).containsExactly("2026-10-09:RTH_CLOSE",
        "2026-10-09:POST_CLOSE", "2026-10-12:PRE_OPEN", "2026-10-12:RTH_OPEN");
    time.set(et("2026-10-09T16:00:00"));
    transitions.run("2026-10-09:RTH_CLOSE");
    OrderDto paused = order(p, o.orderId());
    assertThat(paused.waitReason()).isEqualTo(WaitReason.SESSION_CLOSED);
    assertThat(events.stream(SessionChanged.class)).isNotEmpty();
    time.set(et("2026-10-12T09:30:00"));
    transitions.run("2026-10-12:RTH_OPEN");
    OrderDto resumed = order(p, o.orderId());
    assertThat(resumed.waitReason()).isEqualTo(WaitReason.LATENCY);
    assertThat(resumed.events()).extracting(e -> e.type()).contains(OrderEventType.PAUSED, OrderEventType.RESUMED);
    assertThat(resumed.status()).isEqualTo(OrderStatus.WORKING);
  }

  @Test
  void theDailyJobsRunOnTheEtClockAndCatchUpLatestOnly() {
    ScheduledJob maintenance = job(DailyTradingJobs.DAILY_MAINTENANCE);
    ScheduledJob check = job(DailyTradingJobs.CHECK_INVARIANTS);
    assertThat(maintenance.catchUp()).isEqualTo(CatchUpPolicy.LATEST_ONLY);
    assertThat(check.catchUp()).isEqualTo(CatchUpPolicy.LATEST_ONLY);
    assertThat(transitions.catchUp()).isEqualTo(CatchUpPolicy.ALL_MISSED);
    assertThat(transitions.name()).isEqualTo(SessionTransitionJob.NAME);
    assertThat(maintenance.occurrencesBetween(et("2026-10-09T00:00:00"), et("2026-10-11T00:00:00"), calendar))
        .extracting(JobOccurrence::runKey, JobOccurrence::at)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("2026-10-09", et("2026-10-09T03:30:00")),
            org.assertj.core.groups.Tuple.tuple("2026-10-10", et("2026-10-10T03:30:00")));
    List<JobOccurrence> hourly = check.occurrencesBetween(et("2026-10-09T03:00:00"), et("2026-10-09T06:30:00"),
        calendar);
    assertThat(hourly).extracting(JobOccurrence::runKey).containsExactly("2026-10-09:04", "2026-10-09:05",
        "2026-10-09:06");
    maintenance.run("2026-10-09");
    check.run("2026-10-09:05");
    assertThat(engine.status().lastInvariants().ok()).isTrue();
    assertThat(engine.status().state()).isEqualTo(EngineState.RUNNING);
  }

  private ScheduledJob job(String name) {
    return jobs.stream().filter(j -> j.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void dailyMaintenanceLiftsAnExpiredGfvRestriction() {
    Player p = human();
    jdbc.sql("UPDATE account SET restriction = 'SETTLED_CASH_ONLY', restriction_until = ?, restriction_reason = 'x'"
        + " WHERE id = ?").params(Jdbc.ts(time.now().minusSeconds(60)), p.accountId()).update();
    engine.run(new EngineCommand.DailyMaintenanceCmd(time.todayEt()));
    assertThat(account(p).restriction()).isEqualTo(AccountRestriction.NONE);
    assertThat(account(p).buyingPower()).isEqualByComparingTo(new BigDecimal("500.00"));
  }
}
