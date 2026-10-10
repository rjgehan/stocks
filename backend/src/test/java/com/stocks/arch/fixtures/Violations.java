package com.stocks.arch.fixtures;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Deliberate §5.2 violations, used only by {@code ArchitectureRulesSelfTest} to prove each rule fires. Test sources
 * only; never instantiated.
 */
@SuppressWarnings("unused")
public final class Violations {

  private Violations() {}

  /** Rule 4. */
  public static final class FloatingMoney {
    double price;

    public Double ratio(float f) {
      return (double) f;
    }
  }

  /** Rule 5. */
  public static final class SystemClock {
    Instant now() {
      return Instant.now();
    }

    LocalDate today() {
      return LocalDate.now();
    }

    long millis() {
      return System.currentTimeMillis();
    }
  }

  /** Rule 6. */
  public static final class DoubleDecimal {
    BigDecimal a() {
      return new BigDecimal(0.1);
    }

    BigDecimal b() {
      return BigDecimal.valueOf(0.1);
    }
  }

  /** Rule 9. */
  public static final class RawJdbcTime {
    Instant read(ResultSet rs) throws SQLException {
      return rs.getObject("at", OffsetDateTime.class).toInstant();
    }

    Timestamp ts(ResultSet rs) throws SQLException {
      return rs.getTimestamp("at");
    }
  }

  /** Fine: BigDecimal.valueOf(long) and BigDecimal(String) are allowed. */
  public static final class Allowed {
    BigDecimal a() {
      return BigDecimal.valueOf(5L).add(new BigDecimal("0.10"));
    }
  }
}
