package com.stocks.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.stocks.common.domain.AdjustmentTreatment;
import com.stocks.common.domain.DataQuality;
import com.stocks.common.domain.PlayerKind;
import com.stocks.common.domain.SessionType;
import com.stocks.common.domain.TransferScope;
import com.stocks.common.domain.TransferStatus;
import com.stocks.common.domain.TransferType;
import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import com.stocks.common.jdbc.Jdbc;
import com.stocks.common.web.IdempotencyKeys;
import com.stocks.common.web.Page;
import com.stocks.contract.trading.CashTransferCommand;
import com.stocks.contract.trading.IdempotencyRef;
import com.stocks.contract.trading.TransferFilter;
import com.stocks.contract.trading.dto.CashTransferPreviewDto;
import com.stocks.contract.trading.dto.TransferBatchResultDto;
import com.stocks.contract.trading.dto.TransferRowDto;
import com.stocks.testkit.ContractMocksWebTest;
import com.stocks.testkit.Fixtures;
import com.stocks.testkit.TestTimes;
import com.stocks.testkit.WebSession;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** §21.1/§21.2 admin cash transfers: request validation, delegation to WS3, idempotency fast path, audit. */
class AdminMoneyIT extends ContractMocksWebTest {

  Fixtures.PlayerRef admin;
  WebSession root;

  @BeforeEach
  void setUp() {
    admin = fx.admin(handle("banker"));
    root = adminSession(admin);
  }

  MvcResult apply(String key, String json) {
    var b = post("/api/v1/admin/cash-transfers").contentType(MediaType.APPLICATION_JSON).content(json);
    if (key != null) {
      b.header(IdempotencyKeys.HEADER, key);
    }
    return root.perform(b);
  }

  MvcResult preview(String json) {
    return root.perform(post("/api/v1/admin/cash-transfers/preview").contentType(MediaType.APPLICATION_JSON)
        .content(json));
  }

  static TransferBatchResultDto result(int applied, int skipped) {
    return new TransferBatchResultDto("bat_01TEST", TransferScope.ALL, TransferType.DEPOSIT, new BigDecimal("100.00"),
        "Halloween bonus", TestTimes.START, applied, skipped,
        List.of(new TransferBatchResultDto.TransferResultDto("xfr_01TEST", "alice", "Alice", "acc_01TEST",
            skipped > 0 ? TransferStatus.SKIPPED : TransferStatus.APPLIED, null, new BigDecimal("100.00"),
            new BigDecimal("500.00"), new BigDecimal("1.000000000000000000"), new BigDecimal("600.00"),
            DataQuality.SIMULATED, false, List.of())));
  }

  @Test
  void aDepositToAllDelegatesOneCommandAndIsAudited() {
    when(cashTransfers.apply(any(), any(), anyLong())).thenReturn(result(3, 0));
    MvcResult r = apply("bonus-1", "{\"target\":{\"scope\":\"ALL\"},\"type\":\"DEPOSIT\",\"amount\":\"100\","
        + "\"memo\":\"  Halloween bonus \"}");
    assertThat(status(r)).as(body(r)).isEqualTo(201);
    assertThat((String) json(r, "$.batchId")).isEqualTo("bat_01TEST");
    assertThat((Integer) json(r, "$.applied")).isEqualTo(3);
    ArgumentCaptor<CashTransferCommand> cmd = ArgumentCaptor.forClass(CashTransferCommand.class);
    ArgumentCaptor<IdempotencyRef> ref = ArgumentCaptor.forClass(IdempotencyRef.class);
    verify(cashTransfers).apply(cmd.capture(), ref.capture(), eq(admin.id()));
    CashTransferCommand c = cmd.getValue();
    assertThat(c.scope()).isEqualTo(TransferScope.ALL);
    assertThat(c.handleOrNull()).isNull();
    assertThat(c.kinds()).isEqualTo(EnumSet.allOf(PlayerKind.class));
    assertThat(c.type()).isEqualTo(TransferType.DEPOSIT);
    assertThat(c.amount()).isEqualTo(new BigDecimal("100.00"));
    assertThat(c.treatAs()).isEqualTo(AdjustmentTreatment.CONTRIBUTION);
    assertThat(c.memo()).isEqualTo("Halloween bonus");
    assertThat(c.allOrNothing()).isFalse();
    assertThat(ref.getValue().scope()).isEqualTo("admin.cash-transfer");
    assertThat(ref.getValue().key()).isEqualTo("bonus-1");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE category = 'MONEY' AND actor_player_id = ? "
        + "AND target_id = 'bat_01TEST'").param(admin.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void singleTargetsAndKindsAreResolved() {
    Fixtures.PlayerRef bot = fx.ai(handle("bot"), admin);
    when(cashTransfers.apply(any(), any(), anyLong())).thenReturn(result(1, 0));
    apply("one", "{\"target\":{\"scope\":\"SINGLE\",\"handle\":\"" + bot.handle().toUpperCase() + "\"},"
        + "\"type\":\"WITHDRAWAL\",\"amount\":\"25.50\",\"memo\":\"oops\"}");
    apply("two", "{\"target\":{\"scope\":\"ALL\",\"kinds\":[\"AI\"]},\"type\":\"ADJUSTMENT\",\"amount\":\"-1.25\","
        + "\"treatAs\":\"PNL_CORRECTION\",\"memo\":\"fee refund\",\"allOrNothing\":true}");
    ArgumentCaptor<CashTransferCommand> cmd = ArgumentCaptor.forClass(CashTransferCommand.class);
    verify(cashTransfers, times(2)).apply(cmd.capture(), any(), eq(admin.id()));
    CashTransferCommand single = cmd.getAllValues().get(0);
    assertThat(single.scope()).isEqualTo(TransferScope.SINGLE);
    assertThat(single.handleOrNull()).isEqualTo(bot.handle());
    assertThat(single.amount()).isEqualTo(new BigDecimal("25.50"));
    CashTransferCommand adj = cmd.getAllValues().get(1);
    assertThat(adj.kinds()).isEqualTo(Set.of(PlayerKind.AI));
    assertThat(adj.amount()).isEqualTo(new BigDecimal("-1.25"));
    assertThat(adj.treatAs()).isEqualTo(AdjustmentTreatment.PNL_CORRECTION);
    assertThat(adj.allOrNothing()).isTrue();
  }

  @Test
  void validation() {
    String base = "{\"target\":{\"scope\":\"ALL\"},\"memo\":\"m\",";
    assertThat(code(apply(null, base + "\"type\":\"DEPOSIT\",\"amount\":\"1\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", base + "\"type\":\"INITIAL\",\"amount\":\"1\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", base + "\"type\":\"DEPOSIT\",\"amount\":\"-1\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", base + "\"type\":\"DEPOSIT\",\"amount\":\"1.001\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", base + "\"type\":\"ADJUSTMENT\",\"amount\":\"0\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", base + "\"type\":\"DEPOSIT\",\"amount\":\"5\",\"treatAs\":\"PNL_CORRECTION\"}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("bad key!", base + "\"type\":\"DEPOSIT\",\"amount\":\"5\"}"))).isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", "{\"target\":{\"scope\":\"ALL\"},\"memo\":\"\",\"type\":\"DEPOSIT\",\"amount\":\"5\"}")))
        .isEqualTo("VALIDATION_FAILED");
    assertThat(code(apply("k", "{\"target\":{\"scope\":\"SINGLE\",\"handle\":\"ghost_nobody\"},\"memo\":\"m\","
        + "\"type\":\"DEPOSIT\",\"amount\":\"5\"}"))).isEqualTo("PLAYER_NOT_FOUND");
    assertThat(code(apply("k", "{\"target\":{\"scope\":\"MOON\"},\"memo\":\"m\",\"type\":\"DEPOSIT\",\"amount\":\"5\"}")))
        .isEqualTo("VALIDATION_FAILED");
    verify(cashTransfers, never()).apply(any(), any(), anyLong());
  }

  @Test
  void allOrNothingWithSkipsIs422WithTheResults() {
    when(cashTransfers.apply(any(), any(), anyLong())).thenReturn(result(0, 1));
    MvcResult r = apply("aon", "{\"target\":{\"scope\":\"ALL\"},\"type\":\"WITHDRAWAL\",\"amount\":\"1000\","
        + "\"memo\":\"too much\",\"allOrNothing\":true}");
    assertThat(status(r)).isEqualTo(422);
    assertThat((Integer) json(r, "$.skipped")).isEqualTo(1);
  }

  @Test
  void engineErrorsPassThroughAndAreAudited() {
    when(cashTransfers.apply(any(), any(), anyLong()))
        .thenThrow(ApiException.of(ErrorCode.MARKS_DEGRADED, "stale marks"));
    MvcResult r = apply("degraded", "{\"target\":{\"scope\":\"ALL\"},\"type\":\"DEPOSIT\",\"amount\":\"10\","
        + "\"memo\":\"m\"}");
    assertThat(status(r)).isEqualTo(422);
    assertThat(code(r)).isEqualTo("MARKS_DEGRADED");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE category = 'MONEY' AND outcome = 'REJECTED' "
        + "AND actor_player_id = ?").param(admin.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void aStoredResponseIsReplayedWithoutASecondCommand() {
    when(cashTransfers.apply(any(), any(), anyLong())).thenReturn(result(2, 0));
    String json = "{\"target\":{\"scope\":\"ALL\"},\"type\":\"DEPOSIT\",\"amount\":\"100.00\",\"memo\":\"bonus\"}";
    MvcResult first = apply("replay-me", json);
    ArgumentCaptor<IdempotencyRef> ref = ArgumentCaptor.forClass(IdempotencyRef.class);
    verify(cashTransfers).apply(any(), ref.capture(), anyLong());
    jdbc.sql("""
            INSERT INTO idempotency_record (scope, idem_key, request_hash, status_code, response_body, created_at,
                                            expires_at) VALUES (?, ?, ?, 201, ?, ?, ?)""")
        .params("admin.cash-transfer", "replay-me", ref.getValue().requestHash(), body(first), Jdbc.ts(time.now()),
            Jdbc.ts(time.now().plusSeconds(86400)))
        .update();

    MvcResult again = apply("replay-me", json.replace("\"100.00\"", "\"100\""));
    assertThat(status(again)).isEqualTo(201);
    assertThat(again.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
    assertThat((Integer) json(again, "$.applied")).isEqualTo(2);
    verify(cashTransfers, times(1)).apply(any(), any(), anyLong());

    MvcResult conflict = apply("replay-me", json.replace("bonus", "other"));
    assertThat(status(conflict)).isEqualTo(409);
    assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
  }

  @Test
  void previewAndListDelegate() {
    when(cashTransfers.preview(any())).thenReturn(new CashTransferPreviewDto(TestTimes.START, SessionType.REGULAR,
        List.of()));
    MvcResult p = preview("{\"target\":{\"scope\":\"ALL\",\"kinds\":[\"HUMAN\"]},\"type\":\"DEPOSIT\","
        + "\"amount\":\"5\",\"memo\":\"m\"}");
    assertThat(status(p)).as(body(p)).isEqualTo(200);
    assertThat((String) json(p, "$.session")).isEqualTo("REGULAR");
    verify(cashTransfers).preview(any());
    verify(cashTransfers, never()).apply(any(), any(), anyLong());

    when(cashTransfers.list(any(), any(), anyInt())).thenReturn(new Page<>(List.of(new TransferRowDto("xfr_1",
        "bat_1", "alice", "acc_1", TransferType.DEPOSIT, new BigDecimal("5.00"), true, TransferStatus.APPLIED, null,
        "m", TestTimes.START, admin.handle(), null, null)), null));
    MvcResult l = root.perform(get("/api/v1/admin/cash-transfers?handle=ALICE&limit=500"));
    assertThat(status(l)).as(body(l)).isEqualTo(200);
    assertThat((String) json(l, "$.items[0].transferId")).isEqualTo("xfr_1");
    ArgumentCaptor<TransferFilter> filter = ArgumentCaptor.forClass(TransferFilter.class);
    verify(cashTransfers).list(filter.capture(), any(), eq(200));
    assertThat(filter.getValue().handleOrNull()).isEqualTo("alice");
  }
}
