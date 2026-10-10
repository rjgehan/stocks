package com.stocks.contract.marketdata;

import com.stocks.contract.marketdata.model.CorporateActionData;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The market-data profile's corporate-action feed (§14, §15.4 route {@code corp-actions}): Alpaca's
 * {@code /v1/corporate-actions} on live profiles, the SIM scenarios on {@code simulated}. WS2 provides the bean; WS3's
 * {@code CorporateActionSyncJob} calls it once a day. Network I/O: never call it on the engine thread.
 */
public interface CorporateActionSource {

  /**
   * Corporate actions of {@code symbols} (all symbols when empty) in the provider's {@code [from, to]} window (Alpaca
   * filters on {@code process_date}, §14).
   *
   * @throws RuntimeException when the provider is unreachable (the sync job fails and is retried)
   */
  List<CorporateActionData> corporateActions(Set<String> symbols, LocalDate from, LocalDate to);
}
