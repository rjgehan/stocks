package com.stocks.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

  @Test
  void requireValidatesTheHeader() {
    assertThat(IdempotencyKeys.require(" deposit-2026-10-31:bonus.1 ")).isEqualTo("deposit-2026-10-31:bonus.1");
    assertThat(IdempotencyKeys.require("x".repeat(80))).hasSize(80);
    for (String bad : new String[] {null, "", "   ", "x".repeat(81), "has space", "emoji-😀", "semi;colon"}) {
      assertThatThrownBy(() -> IdempotencyKeys.require(bad)).as(String.valueOf(bad))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fields()).singleElement().satisfies(f -> assertThat(f.field()).isEqualTo("Idempotency-Key"));
          });
    }
  }

  @Test
  void requestHashIsStableAndSeparatesParts() {
    String h = IdempotencyKeys.requestHash("admin.reset", "ply_1", "", "memo");
    assertThat(h).hasSize(64).matches("^[0-9a-f]{64}$");
    assertThat(IdempotencyKeys.requestHash("admin.reset", "ply_1", "", "memo")).isEqualTo(h);
    assertThat(IdempotencyKeys.requestHash("admin.reset", "ply_1", null, "memo")).isEqualTo(h);
    assertThat(IdempotencyKeys.requestHash("admin.reset", "ply_1", "memo", "")).isNotEqualTo(h);
    assertThat(IdempotencyKeys.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
