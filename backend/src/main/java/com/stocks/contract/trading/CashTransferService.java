package com.stocks.contract.trading;

import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferRowDto;

/** Admin cash transfers (§21.2, WS3). */
public interface CashTransferService {

  /** No writes; per-account E⁻, U, marks quality and warnings. */
  CashTransferPreviewDto preview(CashTransferCommand cmd);

  /** One engine command and one transaction per batch; idempotent through {@code idem}. */
  TransferBatchResultDto apply(CashTransferCommand cmd, IdempotencyRef idem, long adminPlayerId);

  Page<TransferRowDto> list(TransferFilter filter, Cursor cursor, int limit);
}
