package com.stocks.contract.trading;

/**
 * §17.6: an {@code Idempotency-Key} for a non-order write. {@code scope} e.g. {@code admin.cash-transfer};
 * {@code requestHash} = hex SHA-256 of the canonical request.
 */
public record IdempotencyRef(String scope, String key, String requestHash) {}
