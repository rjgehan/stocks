package com.stocks.admin;

import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.admin.dto.SystemStatusDto;
import com.stocks.contract.admin.dto.SystemStatusDto.SystemEngineDto;
import com.stocks.contract.admin.dto.SystemStatusDto.SystemInvariantsDto;
import com.stocks.contract.admin.dto.SystemStatusDto.SystemJobDto;
import com.stocks.contract.admin.dto.SystemStatusDto.SystemMarketDataDto;
import com.stocks.contract.admin.dto.SystemStatusDto.SystemProviderDto;
import com.stocks.contract.marketdata.MarketDataService;
import com.stocks.contract.marketdata.MarketDataStatus;
import com.stocks.contract.marketdata.ProviderStatus;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.EngineControl;
import com.stocks.contract.trading.EngineState;
import com.stocks.contract.trading.EngineStatus;
import com.stocks.contract.trading.InvariantResult;
import com.stocks.jobs.JobScheduler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * {@code GET /admin/system} (§21.1 {@code SystemStatus}): build version and start time, the database vendor, the
 * engine and its last invariant check (WS3 {@link EngineControl}), market-data providers, stale symbols and
 * corporate-action holds (WS2 {@link MarketDataService#status()}), the job registry ({@link JobScheduler}) and the
 * number of corporate actions waiting for the admin (WS3). A Day-0 stub shows as a {@code STARTING} engine whose
 * invariants report "not available" rather than failing the page.
 */
@Service
public class SystemStatusService {

  private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

  private final EngineControl engine;
  private final MarketDataService marketData;
  private final CorporateActionQueries corporateActions;
  private final JobScheduler jobs;
  private final DataSource dataSource;
  private final String version;
  private final Instant startedAt;
  private volatile String db;

  public SystemStatusService(EngineControl engine, MarketDataService marketData,
                             CorporateActionQueries corporateActions, JobScheduler jobs, DataSource dataSource,
                             Environment environment, TimeSource time) {
    this.engine = engine;
    this.marketData = marketData;
    this.corporateActions = corporateActions;
    this.jobs = jobs;
    this.dataSource = dataSource;
    this.version = environment.getProperty("stocks.version", "unknown");
    this.startedAt = time.now();
  }

  public SystemStatusDto status() {
    return status(AdminPlayerViews.soft(engine::status, null));
  }

  /** The page around a given engine status (halt and resume return theirs). */
  public SystemStatusDto status(EngineStatus e) {
    SystemEngineDto engineDto;
    SystemInvariantsDto invariants;
    if (e == null) {
      engineDto = new SystemEngineDto(EngineState.STARTING, 0, null, null);
      invariants = new SystemInvariantsDto(null, false, List.of("The trading engine is not available in this build."));
    } else {
      engineDto = new SystemEngineDto(e.state(), e.queueDepth(), e.lastCommandAt(), e.haltReason());
      InvariantResult r = e.lastInvariants();
      invariants = r == null ? new SystemInvariantsDto(null, true, List.of())
          : new SystemInvariantsDto(r.ranAt(), r.ok(), r.failures() == null ? List.of() : r.failures());
    }
    MarketDataStatus md = AdminPlayerViews.soft(marketData::status, null);
    SystemMarketDataDto mdDto = md == null ? new SystemMarketDataDto("unknown", List.of(), List.of(), List.of())
        : new SystemMarketDataDto(md.profile(), nz(md.staleSymbols()), nz(md.corporateActionHolds()),
            nz(md.providers()).stream().map(SystemStatusService::provider).toList());
    List<SystemJobDto> jobDtos = jobs.jobs().stream()
        .map(j -> new SystemJobDto(j.name(), j.lastRunKey(), j.lastStatus(), j.lastFinishedAt(), j.lastDetail()))
        .toList();
    int needingAdmin = AdminPlayerViews.soft(
        () -> corporateActions.list(CorporateActionStatus.NEEDS_ADMIN, null, 200).items().size(), 0);
    return new SystemStatusDto(version, startedAt, db(), engineDto, invariants, mdDto, jobDtos, needingAdmin);
  }

  /** {@code postgresql} or {@code h2} (§21.1), read once from the JDBC metadata. */
  String db() {
    String d = db;
    if (d == null) {
      try (Connection c = dataSource.getConnection()) {
        String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        d = product.contains("postgres") ? SystemStatusDto.DB_POSTGRESQL : SystemStatusDto.DB_H2;
      } catch (SQLException ex) {
        log.warn("Could not read the database product name: {}", ex.toString());
        return "unknown";
      }
      db = d;
    }
    return d;
  }

  private static SystemProviderDto provider(ProviderStatus p) {
    return new SystemProviderDto(p.id(), p.state(), p.streamSymbolsUsed(), p.streamSymbolsMax(), p.restPerMinUsed(),
        p.restPerMinMax(), p.lastMessageAt(), p.capabilities() == null ? Map.of() : p.capabilities(),
        p.probe() == null ? Map.of() : p.probe());
  }

  private static <T> List<T> nz(List<T> list) {
    return list == null ? List.of() : list;
  }
}
