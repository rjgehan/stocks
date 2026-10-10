package com.stocks.trading.corpactions;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.dto.CorporateActionDto;
import java.util.List;
import org.springframework.stereotype.Service;

/** §21.1 {@code GET /admin/corporate-actions} and the corporate-action holds WS2 reads (§14). */
@Service
public class CorporateActionQueriesImpl implements CorporateActionQueries {

  private final CorporateActionStore store;
  private final TimeSource time;

  public CorporateActionQueriesImpl(CorporateActionStore store, TimeSource time) {
    this.store = store;
    this.time = time;
  }

  /** Newest first (by creation); {@code statusOrNull} filters, e.g. NEEDS_ADMIN for the ones to resolve. */
  @Override
  public Page<CorporateActionDto> list(CorporateActionStatus statusOrNull, Cursor cursor, int limit) {
    int n = Page.clampLimit(limit);
    List<CorporateActionRow> rows = store.page(statusOrNull, cursor, n + 1);
    return Page.fromLookahead(rows, n, r -> Cursor.of(r.createdAt(), r.id())).map(this::dto);
  }

  /** NEEDS_ADMIN splits with {@code ex_date ≤ today} (ET). */
  @Override
  public List<String> symbolsOnHold() {
    return store.symbolsOnHold(time.todayEt());
  }

  CorporateActionDto dto(CorporateActionRow r) {
    String resolvedBy = r.resolvedBy() == null ? null : store.handleOf(r.resolvedBy()).orElse(null);
    return new CorporateActionDto(r.publicId(), r.symbol(), r.type(), r.exDate(), r.recordDate(), r.payableDate(),
        r.oldRate(), r.newRate(), r.cashRate(), r.newSymbol(), r.status(), r.origin(), r.detail(), r.createdAt(),
        r.appliedAt(), resolvedBy);
  }
}
