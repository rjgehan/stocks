package com.stocks.admin;

import com.stocks.audit.AuditCategory;
import com.stocks.audit.AuditEntry;
import com.stocks.audit.AuditOutcome;
import com.stocks.audit.AuditService;
import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.money.Money;
import com.stocks.common.web.Cursor;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.common.web.Page;
import com.stocks.common.web.Text;
import com.stocks.contract.security.PlayerPrincipal;
import com.stocks.contract.security.RequestMeta;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.CashTransferService;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.TransferFilter;
import com.stocks.contract.trading.dto.AllTargetRequest;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.CashTransferRequest;
import com.stocks.contract.trading.dto.SingleTargetRequest;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import com.stocks.identity.PlayerRecord;
import com.stocks.identity.PlayerService;
import com.stocks.identity.SettingKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Admin money movements (§21.1, §21.2): validates a {@code CashTransferRequest} into WS3's
 * {@link CashTransferCommand} and delegates preview, apply and list to {@link CashTransferService}. Apply needs an
 * {@code Idempotency-Key} (scope {@code admin.cash-transfer}, §17.6) whose request hash covers every member of the
 * request in canonical form ({@code "100"} and {@code "100.00"} hash alike); a stored response is replayed with
 * {@code Idempotent-Replay: true}. An all-or-nothing batch with skipped accounts answers 422 with the per-account
 * results. Every batch writes one {@code MONEY/CASH_TRANSFER} audit row.
 */
@Service
public class AdminMoneyService {

  static final String SCOPE = "admin.cash-transfer";

  private final CashTransferService transfers;
  private final PlayerService players;
  private final IdempotencyFastPath idempotency;
  private final AuditService audit;

  public AdminMoneyService(CashTransferService transfers, PlayerService players, IdempotencyFastPath idempotency,
                           AuditService audit) {
    this.transfers = transfers;
    this.players = players;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  /**
   * Validates the request (400 {@code VALIDATION_FAILED}, 404 {@code PLAYER_NOT_FOUND} for an unknown handle).
   * DEPOSIT and WITHDRAWAL amounts are positive (the type gives the direction), ADJUSTMENT amounts are signed and
   * non-zero; {@code treatAs} applies to ADJUSTMENT only. The command carries the amount as requested.
   */
  public CashTransferCommand command(CashTransferRequest req) {
    if (req.type() == null || req.type() == TransferType.INITIAL) {
      throw ApiException.validation("type", "DEPOSIT, WITHDRAWAL or ADJUSTMENT");
    }
    BigDecimal amount = Money.require("amount", req.amount());
    if (amount.abs().compareTo(SettingKey.MAX_CASH) > 0) {
      throw ApiException.validation("amount", "At most " + SettingKey.MAX_CASH.toPlainString());
    }
    if (req.type() == TransferType.ADJUSTMENT) {
      if (amount.signum() == 0) {
        throw ApiException.validation("amount", "An adjustment is signed and not zero");
      }
    } else if (amount.signum() <= 0) {
      throw ApiException.validation("amount", "Positive; the type gives the direction");
    }
    AdjustmentTreatment treatAs = req.treatAs() == null ? AdjustmentTreatment.CONTRIBUTION : req.treatAs();
    if (req.type() != TransferType.ADJUSTMENT && treatAs != AdjustmentTreatment.CONTRIBUTION) {
      throw ApiException.validation("treatAs", "Only adjustments can be a P&L correction");
    }
    String memo = AdminText.require(req.memo(), "memo", 1, 200);
    TransferScope scope;
    String handle = null;
    Set<PlayerKind> kinds;
    if (req.target() instanceof SingleTargetRequest single) {
      scope = TransferScope.SINGLE;
      PlayerRecord p = players.requireByHandle(single.handle());
      handle = p.handle();
      kinds = EnumSet.of(p.kind());
    } else if (req.target() instanceof AllTargetRequest all) {
      scope = TransferScope.ALL;
      kinds = all.kinds() == null || all.kinds().isEmpty() ? EnumSet.allOf(PlayerKind.class)
          : EnumSet.copyOf(all.kinds());
    } else {
      throw ApiException.validation("target", "{scope: SINGLE, handle} or {scope: ALL, kinds?}");
    }
    return new CashTransferCommand(scope, handle, Set.copyOf(kinds), req.type(), amount, treatAs, memo,
        Boolean.TRUE.equals(req.allOrNothing()), Boolean.TRUE.equals(req.acceptDegradedMarks()));
  }

  public CashTransferPreviewDto preview(CashTransferRequest req) {
    return transfers.preview(command(req));
  }

  public ResponseEntity<TransferBatchResultDto> apply(CashTransferRequest req, String idempotencyKey,
                                                      PlayerPrincipal admin, RequestMeta meta) {
    String key = IdempotencyKeys.require(idempotencyKey);
    CashTransferCommand cmd = command(req);
    String hash = requestHash(cmd);
    var replay = idempotency.replay(SCOPE, key, hash, TransferBatchResultDto.class);
    if (replay.isPresent()) {
      return replay.get();
    }
    TransferBatchResultDto result;
    try {
      result = transfers.apply(cmd, new IdempotencyRef(SCOPE, key, hash), admin.playerId());
    } catch (ApiException e) {
      audit.record(entry(cmd, admin, meta, AuditOutcome.REJECTED).withDetail("code", e.code().name()));
      throw e;
    }
    boolean rejected = cmd.allOrNothing() && result != null && result.skipped() > 0;
    AuditEntry row = entry(cmd, admin, meta, rejected ? AuditOutcome.REJECTED : AuditOutcome.OK);
    if (result != null) {
      row = row.withTarget("transfer_batch", result.batchId()).withDetail("applied", result.applied())
          .withDetail("skipped", result.skipped());
    }
    audit.record(row);
    return ResponseEntity.status(rejected ? HttpStatus.valueOf(422) : HttpStatus.CREATED).body(result);
  }

  public Page<TransferRowDto> list(String handle, String batchId, Instant from, Instant to, String cursor,
                                   Integer limit) {
    String h = handle == null || handle.isBlank() ? null : PlayerService.normalizeHandle(handle);
    String b = batchId == null || batchId.isBlank() ? null : batchId.trim();
    return transfers.list(new TransferFilter(h, b, from, to), Cursor.parse(cursor), Page.clampLimit(limit));
  }

  /** Canonical request hash: every member, amounts without trailing zeros, kinds sorted. */
  static String requestHash(CashTransferCommand c) {
    String kinds = c.kinds().stream().map(Enum::name).collect(Collectors.toCollection(TreeSet::new)).toString();
    return IdempotencyKeys.requestHash(SCOPE, c.scope().name(), c.handleOrNull(), kinds, c.type().name(),
        c.amount().stripTrailingZeros().toPlainString(), c.treatAs().name(), c.memo(),
        Boolean.toString(c.allOrNothing()), Boolean.toString(c.acceptDegradedMarks()));
  }

  private static AuditEntry entry(CashTransferCommand c, PlayerPrincipal admin, RequestMeta meta,
                                  AuditOutcome outcome) {
    return AuditEntry.of(AuditCategory.MONEY, "CASH_TRANSFER", outcome).withActor(admin).withMeta(meta)
        .withDetail("scope", c.scope().name()).withDetail("handle", c.handleOrNull())
        .withDetail("kinds", new TreeSet<>(c.kinds().stream().map(Enum::name).toList()))
        .withDetail("type", c.type().name()).withDetail("amount", c.amount().toPlainString())
        .withDetail("treatAs", c.treatAs().name()).withDetail("memo", Text.clip(c.memo(), 200))
        .withDetail("allOrNothing", c.allOrNothing());
  }
}
