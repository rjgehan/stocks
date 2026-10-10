package com.stocks.admin.web;

import com.stocks.admin.AdminMoneyService;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.common.web.Page;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.CashTransferRequest;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import com.stocks.security.RecentAuth;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * §21.1/§21.2 fake-money movements: deposit, withdraw or adjust for one player or for ALL (optionally by kind), with
 * a memo every affected player sees. Deposits never change anyone's return (unit-value accounting, §16.2), so the
 * leaderboard stays fair. Preview first; apply needs a recent password check and an {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/admin/cash-transfers")
@Tag(name = "admin", description = "League administration (session + ADMIN only, §21)")
public class AdminMoneyController {

  private final AdminMoneyService money;

  public AdminMoneyController(AdminMoneyService money) {
    this.money = money;
  }

  @PostMapping("/preview")
  @Operation(summary = "Preview a cash transfer (no writes)",
      description = "Per affected account: cash, withdrawable, equity before, unit value, worst mark quality, "
          + "whether it would apply and warnings (ACCOUNT_EQUITY_TOO_LOW, MARKS_DEGRADED). Errors: "
          + "VALIDATION_FAILED, PLAYER_NOT_FOUND.")
  public CashTransferPreviewDto preview(@Valid @RequestBody CashTransferRequest body) {
    return money.preview(body);
  }

  @PostMapping
  @RecentAuth
  @Operation(summary = "Apply a cash transfer to one player or to ALL",
      description = "Recent password check and an Idempotency-Key (reuse it to retry; a replay answers the stored "
          + "result with Idempotent-Replay: true). type DEPOSIT or WITHDRAWAL with a positive amount, or ADJUSTMENT "
          + "(signed; treatAs CONTRIBUTION or PNL_CORRECTION); memo 1-200 characters. 201 with per-account results; "
          + "422 with the results when allOrNothing and an account was skipped. Errors: INSUFFICIENT_WITHDRAWABLE, "
          + "MARKS_DEGRADED (set acceptDegradedMarks to override), IDEMPOTENCY_KEY_CONFLICT.")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TransferBatchResultDto> apply(@Valid @RequestBody CashTransferRequest body,
                                                      @Parameter(description = "Unique per transfer; reuse only to "
                                                          + "retry")
                                                      @RequestHeader(name = IdempotencyKeys.HEADER, required = false)
                                                      String idempotencyKey, HttpServletRequest request) {
    PlayerPrincipal admin = CurrentPlayer.require();
    return money.apply(body, idempotencyKey, admin, RequestMetas.of(request, admin));
  }

  @GetMapping
  @Operation(summary = "Cash transfers", description = "Newest first; filter by player handle, batch id and time.")
  public Page<TransferRowDto> list(@RequestParam(required = false) String handle,
                                   @RequestParam(required = false) String batchId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                   Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                   Instant to,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
    return money.list(handle, batchId, from, to, cursor, limit);
  }
}
