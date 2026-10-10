package com.stocks.common.money;

import com.stocks.common.error.ApiException;
import com.stocks.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cash amounts: {@code BigDecimal} at scale 2, {@code NUMERIC(19,2)} (§6.2). Inputs with more than 2 decimal places
 * are rejected ({@code VALIDATION_FAILED}), never rounded. {@code double} and {@code float} are banned in money code
 * (ArchUnit rule 4); so are {@code new BigDecimal(double)} and {@code BigDecimal.valueOf(double)} (rule 6).
 */
public final class Money {

  public static final int SCALE = 2;
  public static final BigDecimal ZERO = new BigDecimal("0.00");
  public static final BigDecimal ONE_CENT = new BigDecimal("0.01");
  /** The schema allows NUMERIC(19,2): 17 integer digits. */
  public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

  private Money() {}

  /** Parses a decimal string ("500", "500.0", "500.00") to scale 2; more than 2 decimals is an error. */
  public static BigDecimal parse(String field, String value) {
    if (value == null || value.isBlank()) {
      throw ApiException.validation(field, field + " is required");
    }
    BigDecimal v;
    try {
      v = new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      throw ApiException.validation(field, field + " must be a decimal string, e.g. \"500.00\"");
    }
    return require(field, v);
  }

  /** Validates a cash amount (at most 2 decimals, within NUMERIC(19,2)) and normalizes it to scale 2. */
  public static BigDecimal require(String field, BigDecimal value) {
    if (value == null) {
      throw ApiException.validation(field, field + " is required");
    }
    if (!hasAtMostDecimals(value, SCALE)) {
      throw ApiException.of(ErrorCode.VALIDATION_FAILED, field + " has more than 2 decimal places")
          .withField(field, ErrorCode.VALIDATION_FAILED, "At most 2 decimal places");
    }
    BigDecimal v = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    if (v.abs().compareTo(MAX) > 0) {
      throw ApiException.validation(field, field + " is too large");
    }
    return v;
  }

  /** True if the value has no more than {@code decimals} significant decimal places ("1.230" has 2). */
  public static boolean hasAtMostDecimals(BigDecimal value, int decimals) {
    return value.stripTrailingZeros().scale() <= decimals;
  }

  /** Exact scale-2 view of a value that is already in whole cents; throws if it is not. */
  public static BigDecimal cents(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.UNNECESSARY);
  }

  public static boolean isPositive(BigDecimal value) {
    return value.signum() > 0;
  }

  public static boolean isNegative(BigDecimal value) {
    return value.signum() < 0;
  }

  /** Null-safe sum (nulls count as zero); the result has scale ≥ 2. */
  public static BigDecimal sum(BigDecimal... values) {
    BigDecimal total = ZERO;
    for (BigDecimal v : values) {
      if (v != null) {
        total = total.add(v);
      }
    }
    return total;
  }
}
