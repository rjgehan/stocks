package com.stocks.admin.web;

import com.stocks.admin.AdminText;
import com.stocks.admin.SystemStatusService;
import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.admin.dto.HaltEngineRequest;
import com.stocks.contract.admin.dto.RunJobRequest;
import com.stocks.contract.admin.dto.SystemStatusDto;
import com.stocks.contract.jobs.ScheduledJob;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.EngineControl;
import com.stocks.jobs.JobRunGuard;
import com.stocks.jobs.JobScheduler;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * §21.1 system: status (engine, invariants, market-data providers, jobs, corporate actions needing the admin),
 * "Run now" for a scheduled job, and the engine halt/resume switches (WS3 {@link EngineControl}).
 */
@RestController
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminSystemController implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(AdminSystemController.class);

  private final SystemStatusService status;
  private final JobScheduler jobs;
  private final EngineControl engine;
  private final AuditService audit;
  private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

  public AdminSystemController(SystemStatusService status, JobScheduler jobs, EngineControl engine,
                               AuditService audit) {
    this.status = status;
    this.jobs = jobs;
    this.engine = engine;
    this.audit = audit;
  }

  @GetMapping("/api/v1/admin/system")
  @Operation(summary = "System status", description = "Version, start time, database, engine state and queue, last "
      + "invariant check, market-data providers (stream usage, REST budget, probe), stale symbols, corporate-action "
      + "holds, scheduled jobs (last run key and status) and corporate actions waiting for the admin.")
  public SystemStatusDto system() {
    return status.status();
  }

  @PostMapping("/api/v1/admin/system/jobs/{jobName}/run")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @RecentAuth
  @Operation(summary = "Run a scheduled job now",
      description = "Recent password check. Starts one occurrence (runKey, default the latest past occurrence) in "
          + "the background through the job_run guard; an occurrence already DONE does not run again. Watch the "
          + "result in GET /admin/system. Errors: NOT_FOUND, VALIDATION_FAILED.")
  public void runJob(@PathVariable String jobName, @RequestBody(required = false) RunJobRequest body,
                     HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    boolean known = jobs.scheduledJobs().stream().map(ScheduledJob::name).anyMatch(jobName::equals);
    if (!known) {
      throw ApiException.of(ErrorCode.NOT_FOUND, "Unknown job '" + jobName + "'.");
    }
    String runKey = body == null || body.runKey() == null || body.runKey().isBlank() ? null : body.runKey().trim();
    if (runKey != null && !JobScheduler.RUN_KEY.matcher(runKey).matches()) {
      throw ApiException.validation("runKey", "1-48 characters of letters, digits and . _ : + -");
    }
    RequestMeta meta = RequestMetas.of(request, admin);
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "JOB_RUN", AuditOutcome.OK).withActor(admin).withMeta(meta)
        .withTarget("job", jobName).withDetail("runKey", runKey));
    runner.execute(() -> {
      try {
        JobRunGuard.RunResult r = jobs.runNow(jobName, runKey);
        log.info("Admin run of {} {}: {}", jobName, r.runKey(), r.outcome());
      } catch (RuntimeException e) {
        log.warn("Admin run of {} failed: {}", jobName, e.toString());
      }
    });
  }

  @PostMapping("/api/v1/admin/engine/halt")
  @RecentAuth
  @Operation(summary = "Halt the trading engine", description = "Recent password check. Every engine command then "
      + "answers ENGINE_HALTED until resumed.")
  public SystemStatusDto halt(@Valid @RequestBody HaltEngineRequest body, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    String reason = AdminText.require(body.reason(), "reason", 1, 200);
    SystemStatusDto result = status.status(engine.halt(reason, admin.playerId()));
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "ENGINE_HALT", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("engine", null).withDetail("reason", reason));
    return result;
  }

  @PostMapping("/api/v1/admin/engine/resume")
  @RecentAuth
  @Operation(summary = "Resume the trading engine", description = "Recent password check. Runs the invariant check "
      + "first and resumes only if it passes.")
  public SystemStatusDto resume(HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    SystemStatusDto result = status.status(engine.resume(admin.playerId()));
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "ENGINE_RESUME", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("engine", null)
        .withDetail("state", result.engine().status().name()));
    return result;
  }

  @Override
  public void destroy() {
    runner.shutdownNow();
  }
}
