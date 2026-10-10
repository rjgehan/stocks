package com.stocks.trading.corpactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.domain.ActivityType;
import com.stocks.common.domain.CorporateActionStatus;
import com.stocks.common.domain.CorporateActionType;
import com.stocks.common.domain.OrderStatus;
import com.stocks.common.domain.Side;
import com.stocks.common.domain.TimeInForce;
import com.stocks.common.domain.WaitReason;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.contract.marketdata.model.CorporateActionData;
import com.stocks.contract.trading.CorporateActionQueries;
import com.stocks.contract.trading.CorporateActionResolution;
import com.stocks.contract.trading.CorporateActionService;
import com.stocks.contract.trading.dto.ActorKind;
import com.stocks.contract.trading.dto.CorporateActionDto;
import com.stocks.contract.trading.dto.CorporateActionOrigin;
import com.stocks.contract.trading.dto.OrderDto;
import com.stocks.testkit.Fixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The split guard hand-off and the admin's resolution (§14, §21.1): a detected split puts the symbol on a
 * corporate-action hold (new orders rejected, open orders wait with {@code CORPORATE_ACTION_HOLD}) until the admin
 * applies it with confirmed rates (as of now, same arithmetic) or skips it; the sync's NEEDS_ADMIN cases (unsupported
 * types, splits reported after the ex-date), the PENDING refresh, and every resolve error.
 */
class HoldAndResolveTest extends CorporateActionTestSupport {

  @Autowired CorporateActionService service;
  @Autowired CorporateActionQueries queries;

  private long admin() {
    Fixtures.PlayerRef a = fx.human("adm" + next());
    jdbc.sql("UPDATE player SET role = 'ADMIN' WHERE id = ?").param(a.id()).update();
    return a.id();
  }

  @Test
  void aDetectedSplitHoldsTheSymbolUntilTheAdminAppliesIt() {
    Player p = human("5000.00");
    String s = symbol();
    buy(p, s, "10", "100.00");
    OrderDto gtc = place(p, limit(cid(), s, Side.SELL, "5", "150.00", TimeInForce.GTC, false)).order();

    time.set(et("2026-10-12T04:01:00"));                                // SplitGuard after PrevCloseJob
    CorporateActionDto flagged = service.flagDetectedSplit(s.toLowerCase(), MON, new BigDecimal("1"),
        new BigDecimal("2.000"));
    assertThat(flagged.status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    assertThat(flagged.origin()).isEqualTo(CorporateActionOrigin.DETECTED);
    assertThat(flagged.type()).isEqualTo(CorporateActionType.FORWARD_SPLIT);
    assertThat(flagged.newRate()).isEqualByComparingTo("2");
    assertThat(flagged.detail()).contains("SplitGuard");
    assertThat(service.flagDetectedSplit(s, MON, BigDecimal.ONE, new BigDecimal("2")).id())
        .isEqualTo(flagged.id());                                       // idempotent
    assertThat(queries.symbolsOnHold()).contains(s);
    assertThat(queries.list(CorporateActionStatus.NEEDS_ADMIN, null, 200).items())
        .extracting(CorporateActionDto::id).contains(flagged.id());

    // new orders are rejected while the symbol is on hold
    assertThatThrownBy(() -> place(p, market(cid(), s, Side.SELL, "1")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo(ErrorCode.SYMBOL_NOT_TRADABLE);
          assertThat(e.hint()).contains("corporate-action");
        });
    // the open order waits with CORPORATE_ACTION_HOLD (WS2 reports the held symbol as halted)
    time.set(et("2026-10-12T09:31:00"));
    md.halt(s, et("2026-10-12T04:01:00"), null);
    quote(s, "99.98", 1000, "100.00", 1000);
    step();
    time.advance(Duration.ofSeconds(2));
    quote(s, "99.98", 1000, "100.00", 1000);
    step();
    assertThat(order(p, gtc.orderId()).waitReason()).isEqualTo(WaitReason.CORPORATE_ACTION_HOLD);

    long adminId = admin();
    CorporateActionDto applied = service.resolve(flagged.id(), CorporateActionResolution.APPLY, BigDecimal.ONE,
        new BigDecimal("2"), "confirmed on the exchange notice", adminId);
    assertThat(applied.status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(applied.resolvedBy()).isNotNull();
    assertThat(applied.appliedAt()).isEqualTo(now());
    assertThat(applied.detail()).contains("APPLY 2-for-1").contains("confirmed on the exchange notice");
    assertThat(queries.symbolsOnHold()).doesNotContain(s);
    assertThat(order(p, gtc.orderId()).status()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(accountQueries.positions(p.accountId()).stream().filter(x -> x.symbol().equals(s)).findFirst()
        .orElseThrow().qty()).isEqualByComparingTo("20");
    assertThat(activities(p, ActivityType.SPLIT)).singleElement()
        .satisfies(a -> assertThat(a.by()).isEqualTo(ActorKind.ADMIN));
    // the guard runs again at 09:20: the split is applied, nothing new is flagged
    assertThat(service.flagDetectedSplit(s, MON, BigDecimal.ONE, new BigDecimal("2")).status())
        .isEqualTo(CorporateActionStatus.APPLIED);
    // resolving twice is refused
    assertThatThrownBy(() -> service.resolve(flagged.id(), CorporateActionResolution.SKIP, null, null, "again",
        adminId)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE));
  }

  @Test
  void skippingADetectedSplitReleasesTheHoldAndSticks() {
    String s = symbol();
    time.set(et("2026-10-12T04:01:00"));
    CorporateActionDto flagged = service.flagDetectedSplit(s, MON, new BigDecimal("10"), BigDecimal.ONE);
    assertThat(flagged.type()).isEqualTo(CorporateActionType.REVERSE_SPLIT);
    assertThat(queries.symbolsOnHold()).contains(s);
    CorporateActionDto skipped = service.resolve(flagged.id(), CorporateActionResolution.SKIP, null, null,
        "bad bar data", admin());
    assertThat(skipped.status()).isEqualTo(CorporateActionStatus.SKIPPED);
    assertThat(skipped.detail()).contains("SKIP").contains("bad bar data");
    assertThat(queries.symbolsOnHold()).doesNotContain(s);
    // the 09:20 re-check finds the same difference: the admin's SKIP stands
    time.set(et("2026-10-12T09:20:00"));
    assertThat(service.flagDetectedSplit(s, MON, new BigDecimal("10"), BigDecimal.ONE).status())
        .isEqualTo(CorporateActionStatus.SKIPPED);
    assertThat(store.splitsOn(s, MON)).hasSize(1);
  }

  @Test
  void aProviderSplitStillPendingAtTheGuardNeedsTheAdminAndTheDuplicateIsSuperseded() {
    Player p = human("1000.00");
    String s = symbol();
    buy(p, s, "3", "30.00");
    String provider = split(s, MON, "1", "3");
    time.set(et("2026-10-12T04:01:00"));                                // the 03:45 application did not happen
    CorporateActionDto flagged = service.flagDetectedSplit(s, MON, BigDecimal.ONE, new BigDecimal("3"));
    assertThat(flagged.id()).isEqualTo(provider);
    assertThat(flagged.status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    assertThat(flagged.origin()).isEqualTo(CorporateActionOrigin.PROVIDER);
    long adminId = admin();
    service.resolve(provider, CorporateActionResolution.APPLY, BigDecimal.ONE, new BigDecimal("3"), "ok", adminId);
    assertThat(accountQueries.positions(p.accountId()).getFirst().qty()).isEqualByComparingTo("9");
    // a late provider report of the same split is recorded as SKIPPED (superseded), not applied twice
    String late = "test:late:" + next();
    sync.upsert(List.of(new CorporateActionData(late, s, CorporateActionType.FORWARD_SPLIT, MON, MON, MON,
        BigDecimal.ONE, new BigDecimal("3"), null, null)));
    CorporateActionRow dup = store.byProviderRef(late).orElseThrow();
    assertThat(dup.status()).isEqualTo(CorporateActionStatus.SKIPPED);
    assertThat(dup.detail()).contains("superseded by " + provider);
  }

  @Test
  void theSyncSendsUnsupportedTypesAndLateSplitsToTheAdminAndRefreshesPendingRows() {
    String s = symbol();
    String merger = record(s, CorporateActionType.CASH_MERGER, WED, null, null, null, null, null);
    assertThat(row(merger).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    assertThat(row(merger).detail()).contains("not applied automatically");
    // APPLY is for splits only; SKIP closes it
    long adminId = admin();
    assertThatThrownBy(() -> service.resolve(merger, CorporateActionResolution.APPLY, BigDecimal.ONE,
        new BigDecimal("2"), "x", adminId)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.CORPORATE_ACTION_NOT_RESOLVABLE));
    assertThat(service.resolve(merger, CorporateActionResolution.SKIP, null, null, "cash merger handled by hand",
        adminId).status()).isEqualTo(CorporateActionStatus.SKIPPED);

    // a split reported after its ex-date's pre-open stays PENDING for the engine: nobody held it at the cut-off, so
    // it only adjusts prices (no hold); a hold needs holders that traded since (LateCorporateActionTest)
    time.set(et("2026-10-12T10:00:00"));
    String noHolders = split(s, MON, "1", "2");
    assertThat(row(noHolders).status()).isEqualTo(CorporateActionStatus.PENDING);
    assertThat(apply(noHolders).status()).isEqualTo(CorporateActionStatus.APPLIED);
    assertThat(queries.symbolsOnHold()).doesNotContain(s);
    // the validation of an APPLY, on a split the guard flagged for another symbol
    String h = symbol();
    String lateSplit = service.flagDetectedSplit(h, MON, BigDecimal.ONE, new BigDecimal("2")).id();
    assertThat(queries.symbolsOnHold()).contains(h);
    assertThatThrownBy(() -> service.resolve(lateSplit, CorporateActionResolution.APPLY, null, BigDecimal.ONE,
        "missing", adminId)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> service.resolve(lateSplit, CorporateActionResolution.APPLY, BigDecimal.ONE,
        BigDecimal.ONE, "equal", adminId)).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    assertThatThrownBy(() -> service.resolve("cac_nope", CorporateActionResolution.SKIP, null, null, "x", adminId))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    service.resolve(lateSplit, CorporateActionResolution.SKIP, null, null, "not real", adminId);

    // a PENDING row takes the provider's latest data; a resolved one is left alone
    time.set(com.stocks.testkit.TestTimes.START);
    String ref = "test:refresh:" + next();
    sync.upsert(List.of(new CorporateActionData(ref, s, CorporateActionType.CASH_DIVIDEND, WED, WED, null, null,
        null, new BigDecimal("0.10"), null)));
    CorporateActionSyncJob.Result again = sync.upsert(List.of(new CorporateActionData(ref, s,
        CorporateActionType.CASH_DIVIDEND, WED, WED, WED.plusDays(7), null, null, new BigDecimal("0.12"), null),
        new CorporateActionData(null, s, CorporateActionType.CASH_DIVIDEND, WED, null, null, null, null, null, null)));
    assertThat(again.refreshed()).isEqualTo(1);
    assertThat(again.ignored()).isEqualTo(1);
    CorporateActionRow refreshed = store.byProviderRef(ref).orElseThrow();
    assertThat(refreshed.cashRate()).isEqualByComparingTo("0.12");
    assertThat(refreshed.payableDate()).isEqualTo(WED.plusDays(7));
    service.resolve(applyAndExpectNeedsAdmin(refreshed.publicId()), CorporateActionResolution.SKIP, null, null,
        "test cleanup", adminId);
  }

  /** A dividend whose ex-date has passed with no holders: applied (nothing accrues). Returns a NEEDS_ADMIN id. */
  private String applyAndExpectNeedsAdmin(String dividendId) {
    // a dividend that lost its payable date cannot be applied: NEEDS_ADMIN
    jdbc.sql("UPDATE corporate_action SET payable_date = NULL WHERE public_id = ?").param(dividendId).update();
    time.set(et("2026-10-14T03:45:00"));
    assertThat(apply(dividendId).status()).isEqualTo(CorporateActionStatus.NEEDS_ADMIN);
    return dividendId;
  }
}
