package com.stocks.admin.web;

import com.stocks.admin.AdminText;
import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.error.ApiException;
import com.stocks.common.time.TimeSource;
import com.stocks.contract.admin.dto.CreateCalendarOverrideRequest;
import com.stocks.contract.calendar.CalendarAdmin;
import com.stocks.contract.calendar.CalendarOverride;
import com.stocks.contract.calendar.dto.CalendarOverrideDto;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.jobs.JobScheduler;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1 ad-hoc market closures and shortened sessions (§9.1), through WS2's {@link CalendarAdmin}; every change
 * re-plans the job scheduler so session transitions follow the new calendar.
 */
@RestController
@RequestMapping("/api/v1/admin/calendar/overrides")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminCalendarController {

  static final int MAX_DAYS = 400;

  private final CalendarAdmin calendar;
  private final JobScheduler jobs;
  private final AuditService audit;
  private final TimeSource time;

  public AdminCalendarController(CalendarAdmin calendar, JobScheduler jobs, AuditService audit, TimeSource time) {
    this.calendar = calendar;
    this.jobs = jobs;
    this.audit = audit;
    this.time = time;
  }

  @GetMapping
  @Operation(summary = "Calendar overrides", description = "from/to are ET dates (default: today to a year ahead; "
      + "at most 400 days).")
  public List<CalendarOverrideDto> list(@RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    LocalDate f = from == null ? time.todayEt().minusDays(30) : from;
    LocalDate t = to == null ? f.plusDays(365) : to;
    if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > MAX_DAYS) {
      throw ApiException.validation("to", "to must be on or after from, at most " + MAX_DAYS + " days later");
    }
    return calendar.overrides(f, t).stream().map(AdminCalendarController::dto).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RecentAuth
  @Operation(summary = "Close the market or shorten a session on a date",
      description = "Recent password check. closed=true closes the day; otherwise rthCloseAt/postCloseAt shorten "
          + "it. reason 1-200 characters. Errors: VALIDATION_FAILED.")
  public CalendarOverrideDto create(@Valid @RequestBody CreateCalendarOverrideRequest body,
                                    HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    String reason = AdminText.require(body.reason(), "reason", 1, 200);
    CalendarOverride created = calendar.putOverride(body.date(), body.closed(), body.rthCloseAt(),
        body.postCloseAt(), reason, admin.playerId());
    jobs.replan();
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "CALENDAR_OVERRIDE", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("calendar", body.date().toString())
        .withDetail("closed", body.closed()).withDetail("reason", reason));
    return dto(created);
  }

  @DeleteMapping("/{date}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RecentAuth
  @Operation(summary = "Remove a calendar override", description = "Recent password check. Errors: NOT_FOUND.")
  public void delete(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                     HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    calendar.deleteOverride(date, admin.playerId());
    jobs.replan();
    audit.record(AuditEntry.of(AuditCategory.ADMIN, "CALENDAR_OVERRIDE_DELETE", AuditOutcome.OK).withActor(admin)
        .withMeta(RequestMetas.of(request, admin)).withTarget("calendar", date.toString()));
  }

  static CalendarOverrideDto dto(CalendarOverride o) {
    return new CalendarOverrideDto(o.date(), o.closed(), o.rthCloseAt(), o.postCloseAt(), o.reason(),
        o.createdByHandle(), o.createdAt());
  }
}
