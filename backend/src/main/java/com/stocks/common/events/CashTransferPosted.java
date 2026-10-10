package com.stocks.common.events;

import com.stocks.common.domain.TransferType;
import java.math.BigDecimal;
import java.time.Instant;

/** An external cash flow was applied to an account (§5.3, §21.2). Ids are public ids. */
public record CashTransferPosted(long accountId, long playerId, String transferId, String batchId, TransferType type,
                                 BigDecimal amount, String memo, Instant at) {}
