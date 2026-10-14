package com.stocks.contract.admin.dto;

import com.stocks.contract.identity.dto.ApiKeyInfoDto;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import java.time.LocalDate;
import java.util.List;

/** §21.1 {@code AdminPlayerDetail}: no live positions or orders; see GET /admin/players/{h}/portfolio. */
public record AdminPlayerDetailDto(AdminPlayerDto player, boolean apiTradingAllowed, List<RunDto> runs,
                                   AccountDto balances, LocalDate holdingsAsOf, List<EodHoldingDto> eodHoldings,
                                   List<TransferRowDto> transfers, List<ApiKeyInfoDto> apiKeys,
                                   List<AuditRowDto> recentAudit) {}
