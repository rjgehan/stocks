package com.stocks.trading.web;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.time.TimeSource;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.Page;
import com.stocks.contract.identity.SettingsService;
import com.stocks.contract.security.CurrentPlayer;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.trading.AccountQueries;
import com.stocks.contract.trading.AccountService;
import com.stocks.contract.trading.ActivityFilter;
import com.stocks.contract.trading.Balances;
import com.stocks.contract.trading.ExecutionFilter;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.OrderQueries;
import com.stocks.contract.trading.OrderService;
import com.stocks.contract.trading.dto.AccountDto;
import com.stocks.contract.trading.dto.ActivityDto;
import com.stocks.contract.trading.dto.ClosePositionRequest;
import com.stocks.contract.trading.dto.ExecutionDto;
import com.stocks.contract.trading.dto.PlaceOrderResponse;
import com.stocks.contract.trading.dto.PositionDto;
import com.stocks.contract.trading.dto.ResetAccountRequest;
import com.stocks.contract.trading.dto.RunDto;
import com.stocks.contract.trading.dto.ViolationDto;
import com.stocks.contract.trading.dto.ViolationsDto;
import com.stocks.security.RequestMetas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §18.2 account: the live account, balances, positions, activities, executions, violations, runs and self-reset. */
@RestController
@RequestMapping("/api/v1/account")
@Tag(name = "account", description = "The caller's account (the active run)")
public class AccountController {

  /** §6.6: 1–80 of {@code [A-Za-z0-9._:-]}. */
  static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,80}$");
  static final String SELF_RESET_SCOPE = "account.reset";

  private final AccountQueries accounts;
  private final AccountService accountService;
  private final OrderQueries orders;
  private final OrderService orderService;
  private final SettingsService settings;
  private final TimeSource time;

  public AccountController(AccountQueries accounts, AccountService accountService, OrderQueries orders,
                           OrderService orderService, SettingsService settings, TimeSource time) {
    this.accounts = accounts;
    this.accountService = accountService;
    this.orders = orders;
    this.orderService = orderService;
    this.settings = settings;
    this.time = time;
  }

  private long accountId() {
    return accounts.activeAccountId(CurrentPlayer.require().playerId());
  }

  @GetMapping
  @Operation(summary = "The account, valued live", description = "Equity, cash (settled / unsettled), buying power, "
      + "run-relative P&L and return, day P&L, GFV status.")
  public AccountDto account() {
    return accounts.account(accountId());
  }

  @GetMapping("/balances")
  @Operation(summary = "Cash balances (§10.2)", description = "From the ledger and open orders only (no marks): "
      + "settled and unsettled cash, holds, buying power and withdrawable cash as of today (ET).")
  public Balances balances() {
    return accounts.balances(accountId(), time.todayEt());
  }

  @GetMapping("/positions")
  @Operation(summary = "Open positions")
  public List<PositionDto> positions() {
    return accounts.positions(accountId());
  }

  @GetMapping("/positions/{symbol}")
  @Operation(summary = "One position")
  public PositionDto position(@PathVariable String symbol) {
    String sym = OrderController.upper(symbol);
    return accounts.positions(accountId()).stream().filter(p -> p.symbol().equals(sym)).findFirst()
        .orElseThrow(() -> ApiException.of(ErrorCode.NOT_FOUND, "No open position in " + sym + "."));
  }

  @PostMapping("/positions/{symbol}/close")
  @Operation(summary = "Close a position", description = "A MARKET SELL of the full available quantity; validated and "
      + "idempotent like POST /orders (201, or 200 on replay).")
  public ResponseEntity<PlaceOrderResponse> close(@PathVariable String symbol,
                                                  @RequestBody ClosePositionRequest request,
                                                  HttpServletRequest http) {
    PlayerPrincipal who = CurrentPlayer.require();
    return OrderController.created(orderService.closePosition(who, symbol, request.clientOrderId(),
        request.rationale(), RequestMetas.of(http, who)));
  }

  @GetMapping("/activities")
  @Operation(summary = "Account activity", description = "Ledger rows (fills, transfers, dividends) merged with "
      + "account events (splits, GFVs, resets, restrictions, admin views), newest first.")
  public Page<ActivityDto> activities(@RequestParam(required = false) Set<ActivityType> types,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
    return accounts.activities(accountId(), new ActivityFilter(types == null ? Set.of() : types, from, to),
        Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/executions")
  @Operation(summary = "Executions (fills)", description = "Newest first.")
  public Page<ExecutionDto> executions(@RequestParam(required = false) String symbol,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                       @RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer limit) {
    return orders.executions(accountId(), new ExecutionFilter(OrderController.upper(symbol), from, to),
        Cursor.parse(cursor), Page.clampLimit(limit));
  }

  @GetMapping("/violations")
  @Operation(summary = "Good-faith violations", description = "The restriction and the active / expired GFVs.")
  public ViolationsDto violations() {
    long id = accountId();
    AccountDto acc = accounts.account(id);
    Instant now = time.now();
    List<ViolationDto> all = accounts.violations(id);
    return new ViolationsDto(acc.restriction(), acc.restrictionUntil(),
        all.stream().filter(v -> v.expiresAt().isAfter(now)).toList(),
        all.stream().filter(v -> !v.expiresAt().isAfter(now)).toList());
  }

  @GetMapping("/runs")
  @Operation(summary = "Every run (resets open a new one)")
  public List<RunDto> runs() {
    return accounts.runs(CurrentPlayer.require().playerId());
  }

  @PostMapping("/reset")
  @Operation(summary = "Reset your own account", description = "Only when league.allowSelfReset is on. Requires "
      + "Idempotency-Key and {\"confirm\": \"RESET\"}. Closes the run and opens a new one at the same unit value.")
  public AccountDto reset(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                          @RequestBody ResetAccountRequest request) {
    PlayerPrincipal who = CurrentPlayer.require();
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw ApiException.validation("Idempotency-Key", "Idempotency-Key header is required: 1–80 of [A-Za-z0-9._:-]");
    }
    if (request == null || !ResetAccountRequest.CONFIRM.equals(request.confirm())) {
      throw ApiException.validation("confirm", "confirm must be \"RESET\"");
    }
    if (!allowSelfReset()) {
      throw ApiException.of(ErrorCode.SELF_RESET_DISABLED, "Self-reset is disabled in this league.")
          .withHint("Ask the admin to reset your account.");
    }
    IdempotencyRef idem = new IdempotencyRef(SELF_RESET_SCOPE, key, sha256("RESET:" + who.playerId()));
    return accountService.reset(who.playerId(), null, "Self reset", idem, who.playerId());
  }

  private boolean allowSelfReset() {
    try {
      return settings.allowSelfReset();
    } catch (ApiException e) {
      if (e.code() == ErrorCode.NOT_IMPLEMENTED) {
        return false;
      }
      throw e;
    }
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
