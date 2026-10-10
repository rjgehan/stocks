package com.stocks.contract.trading;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.dto.CorporateActionDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface CorporateActionQueries {

  Page<CorporateActionDto> list(CorporateActionStatus statusOrNull, Cursor cursor, int limit);

  /** NEEDS_ADMIN splits with {@code ex_date <= today} (§14). */
  List<String> symbolsOnHold();

  /**
   * The same holds, each with the earliest ex-date of its NEEDS_ADMIN splits (§14): a held symbol is marked at its
   * last day-end mark dated before that ex-date.
   */
  Map<String, LocalDate> holds();
}
