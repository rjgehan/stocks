package com.stocks.contract.trading.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /account/reset} + {@code Idempotency-Key} (§18.2): {@code confirm} must be {@code "RESET"}. */
public record ResetAccountRequest(@NotBlank String confirm) {

  public static final String CONFIRM = "RESET";
}
