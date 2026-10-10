package com.stocks.trading.account;

import com.stocks.common.domain.LedgerEntryType;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.error.ProblemField;
import com.stocks.common.events.AccountChanged;
import com.stocks.common.events.CashTransferPosted;
import com.stocks.common.id.Prefix;
import com.stocks.common.id.PublicId;
import com.stocks.common.money.Money;
import com.stocks.common.time.Et;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto.TransferResultDto;
import com.stocks.trading.engine.EngineTx;
import com.stocks.trading.engine.write.LedgerWriter;
import com.stocks.trading.engine.write.TransferWriter;
import com.stocks.trading.order.TradingClock;
import com.stocks.trading.store.IdempotencyStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code ApplyCashTransfersCmd} (§21.2): one engine command and one transaction per batch. Cash is never edited
 * directly: every applied flow is a {@code cash_transfer} row plus a settled-immediately {@code cash_ledger} row, and
 * the unit accounting of §16.2 keeps every player's return unchanged by the flow.
 */
@Component
public class TransferCommands {

  public static final String SCOPE = "admin.cash-transfer";

  private final TransferPlanner planner;
  private final TransferWriter transfers;
  private final LedgerWriter ledger;
  private final IdempotencyStore idempotency;
  private final TradingClock clock;
  private final PublicId publicIds;
  private final JsonMapper json;

  public TransferCommands(TransferPlanner planner, TransferWriter transfers, LedgerWriter ledger,
                          IdempotencyStore idempotency, TradingClock clock, PublicId publicIds, JsonMapper json) {
    this.planner = planner;
    this.transfers = transfers;
    this.ledger = ledger;
    this.idempotency = idempotency;
    this.clock = clock;
    this.publicIds = publicIds;
    this.json = json;
  }

  public TransferBatchResultDto apply(CashTransferCommand raw, IdempotencyRef idem, long adminPlayerId, Instant now,
                                      EngineTx tx) {
    // 1. idempotency (§17.6), authoritative inside the command
    if (idem != null) {
      var stored = idempotency.find(idem.scope(), idem.key());
      if (stored.isPresent()) {
        if (!stored.get().requestHash().equals(idem.requestHash())) {
          throw conflict();
        }
        return json.readValue(stored.get().responseBody(), TransferBatchResultDto.class);
      }
      if (idempotency.batchKeyUsed(idem.key())) {
        throw conflict();   // the 7-day record expired, but keys are single-use forever (§17.6 backstop)
      }
    }
    CashTransferCommand cmd = TransferPlanner.validate(raw);
    // 2–5. plan
    TransferPlanner.Plan plan = planner.plan(cmd, now, clock.session(now).type());
    if (plan.accounts().isEmpty()) {
      throw ApiException.of(ErrorCode.PLAYER_NOT_FOUND, "No account matches the transfer target.");
    }
    if (plan.degraded() && !cmd.acceptDegradedMarks()) {
      List<ProblemField> fields = plan.accounts().stream().filter(TransferPlanner.Planned::degraded)
          .map(p -> new ProblemField(p.player().handle(), ErrorCode.MARKS_DEGRADED.name(),
              String.join(", ", p.degradedSymbols())))
          .toList();
      throw ApiException.of(ErrorCode.MARKS_DEGRADED, "Some accounts would be valued with degraded marks; the unit "
              + "value fixed at this flow would be wrong permanently.")
          .withHint("Transfer during regular hours or after the 20:20 ET day-end marks, or set acceptDegradedMarks.")
          .withFields(fields);
    }
    BigDecimal requested = cmd.type() == TransferType.ADJUSTMENT ? cmd.amount() : cmd.amount().abs();
    String batchPublicId = publicIds.next(Prefix.TRANSFER_BATCH);
    String kinds = cmd.scope() == com.stocks.common.domain.TransferScope.ALL
        ? cmd.kinds().stream().map(Enum::name).sorted().collect(Collectors.joining(",")) : null;
    long batchId = transfers.insertBatch(new TransferWriter.NewBatch(batchPublicId, cmd.scope(), cmd.type(),
        requested, cmd.treatAs(), cmd.memo(), kinds, cmd.allOrNothing(), idem == null ? null : idem.key(),
        adminPlayerId, now));
    LocalDate today = Et.date(now);
    List<TransferResultDto> results = new ArrayList<>();
    List<ProblemField> skippedFields = new ArrayList<>();
    int applied = 0;
    int skipped = 0;
    for (TransferPlanner.Planned p : plan.accounts()) {
      String xfr = publicIds.next(Prefix.CASH_TRANSFER);
      boolean apply = p.wouldApply();
      BigDecimal equity = p.valuation().equity().setScale(Money.SCALE, RoundingMode.HALF_EVEN);
      long transferId = transfers.insertTransfer(new TransferWriter.NewTransfer(xfr, batchId, p.account().id(),
          cmd.type(), p.amount(), p.contribution(), apply ? TransferStatus.APPLIED : TransferStatus.SKIPPED,
          p.skipReason(), cmd.memo(), equity, p.unitValue(), apply ? p.unitsDelta() : BigDecimal.ZERO,
          apply ? p.unitSeriesAfter() : p.account().unitSeries(), apply && p.rebased(), p.marksQuality(),
          plan.spyMark(), now, adminPlayerId));
      BigDecimal cashAfter = p.account().cashBalance();
      if (apply) {
        cashAfter = ledger.append(p.account().id(), ledgerType(cmd.type()), p.amount(), today, today, now, transferId,
            null, null, cmd.memo(), "XFER:" + xfr).balanceAfter();
        transfers.applyFlow(p.account().id(), p.contribution() ? p.amount() : BigDecimal.ZERO, p.unitsDelta(),
            p.unitSeriesAfter());
        applied++;
        tx.publish(new CashTransferPosted(p.account().id(), p.player().id(), xfr, batchPublicId, cmd.type(),
            p.amount(), cmd.memo(), now));
        tx.publish(new AccountChanged(p.account().id(), p.player().id(), "TRANSFER", now));
      } else {
        skipped++;
        skippedFields.add(new ProblemField(p.player().handle(), ErrorCode.INSUFFICIENT_WITHDRAWABLE.name(),
            "Withdrawable $" + p.balances().withdrawable().toPlainString()));
      }
      results.add(new TransferResultDto(xfr, p.player().handle(), p.player().displayName(), p.account().publicId(),
          apply ? TransferStatus.APPLIED : TransferStatus.SKIPPED, p.skipReason(), p.amount(), equity, p.unitValue(),
          cashAfter, p.marksQuality(), apply && p.rebased(), p.warnings()));
    }
    // 6. all-or-nothing rolls the whole batch back
    if (cmd.allOrNothing() && skipped > 0) {
      throw ApiException.of(ErrorCode.INSUFFICIENT_WITHDRAWABLE, skipped + " of " + plan.accounts().size()
              + " accounts cannot cover the withdrawal; nothing was applied (allOrNothing).")
          .withFields(skippedFields);
    }
    transfers.setBatchCounts(batchId, applied, skipped);
    TransferBatchResultDto result = new TransferBatchResultDto(batchPublicId, cmd.scope(), cmd.type(), requested,
        cmd.memo(), now, applied, skipped, results);
    // 7. the idempotency record, in the same transaction
    if (idem != null) {
      String body = json.writeValueAsString(result);
      if (body.length() > IdempotencyStore.MAX_BODY) {
        // a very large league: the replay keeps the batch and its counts, without the per-account rows
        body = json.writeValueAsString(new TransferBatchResultDto(batchPublicId, cmd.scope(), cmd.type(), requested,
            cmd.memo(), now, applied, skipped, List.of()));
      }
      idempotency.insert(idem.scope(), idem.key(), idem.requestHash(), 201, body, now);
    }
    return result;
  }

  static LedgerEntryType ledgerType(TransferType t) {
    return switch (t) {
      case DEPOSIT -> LedgerEntryType.DEPOSIT;
      case WITHDRAWAL -> LedgerEntryType.WITHDRAWAL;
      case ADJUSTMENT -> LedgerEntryType.ADJUSTMENT;
      case INITIAL -> LedgerEntryType.INITIAL_FUNDING;
    };
  }

  static ApiException conflict() {
    return ApiException.of(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
            "This Idempotency-Key was already used for a different request.")
        .withHint("Use a new Idempotency-Key for a new transfer.");
  }
}
