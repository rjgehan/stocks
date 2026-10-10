package com.stocks.contract.trading;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.dto.CorporateActionDto;
import java.util.List;

public interface CorporateActionQueries {

  Page<CorporateActionDto> list(CorporateActionStatus statusOrNull, Cursor cursor, int limit);

  /** NEEDS_ADMIN splits with {@code ex_date <= today} (§14). */
  List<String> symbolsOnHold();
}
