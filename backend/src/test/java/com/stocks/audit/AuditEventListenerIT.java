package com.stocks.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.common.domain.AccountRestriction;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.ViolationType;
import com.stocks.common.events.CorporateActionApplied;
import com.stocks.common.events.ViolationIssued;
import com.stocks.config.AsyncConfig;
import com.stocks.testkit.ContractMocksWebTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;

/** Engine events become {@code ACCOUNT} audit rows on the {@code eventsExecutor}, off the publishing thread. */
class AuditEventListenerIT extends ContractMocksWebTest {

  @Autowired ApplicationEventPublisher events;
  @Autowired ApplicationContext context;

  @Test
  void violationsAndCorporateActionsAreAuditedAsynchronously() throws Exception {
    events.publishEvent(new ViolationIssued(1, 2, "vio_TEST", ViolationType.GFV, AccountRestriction.SETTLED_CASH_ONLY,
        time.now()));
    events.publishEvent(new CorporateActionApplied("cac_TEST", "NVDA", CorporateActionType.FORWARD_SPLIT,
        time.now()));
    List<String> rows = List.of();
    for (int i = 0; i < 100 && rows.size() < 2; i++) {
      Thread.sleep(20);
      rows = jdbc.sql("SELECT action || ':' || target_id FROM audit_log WHERE target_id IN ('vio_TEST', 'cac_TEST') "
          + "ORDER BY action").query(String.class).list();
    }
    assertThat(rows).containsExactly("CORPORATE_ACTION_APPLIED:cac_TEST", "GFV_ISSUED:vio_TEST");
    assertThat(context.containsBean(AsyncConfig.EVENTS_EXECUTOR)).isTrue();
    assertThat(context.containsBean("applicationTaskExecutor")).as("Boot's executor did not back off").isTrue();
  }
}
