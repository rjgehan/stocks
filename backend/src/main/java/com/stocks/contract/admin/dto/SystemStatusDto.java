package com.stocks.contract.admin.dto;

import com.stocks.contract.trading.EngineState;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** §21.1 {@code SystemStatus} ({@code GET /admin/system}, engine halt/resume responses). {@code db}: {@code postgresql} or {@code h2}. */
public record SystemStatusDto(String version, Instant startedAt, String db, SystemEngineDto engine,
                              SystemInvariantsDto invariants, SystemMarketDataDto marketData, List<SystemJobDto> jobs,
                              int corporateActionsNeedingAdmin) {

  public static final String DB_POSTGRESQL = "postgresql";
  public static final String DB_H2 = "h2";

  public record SystemEngineDto(EngineState status, int queueDepth, Instant lastCommandAt, String haltReason) {}

  public record SystemInvariantsDto(Instant lastRunAt, boolean ok, List<String> failures) {}

  public record SystemMarketDataDto(String profile, List<String> staleSymbols, List<String> corporateActionHolds,
                                    List<SystemProviderDto> providers) {}

  public record SystemProviderDto(String id, String state, int streamSymbolsUsed, int streamSymbolsMax,
                                  int restPerMinUsed, int restPerMinMax, Instant lastMessageAt,
                                  Map<String, Object> capabilities, Map<String, Object> probe) {}

  /** From the {@code JobScheduler} registry. */
  public record SystemJobDto(String name, String lastRunKey, String status, Instant finishedAt, String detail) {}
}
