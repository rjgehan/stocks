package com.stocks.arch.fixtures;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

  /** Rule 4, hidden in arrays and generic type arguments. */
  public static final class HiddenFloatingMoney {
    double[] prices;
    List<Double> marks;

    public Map<String, Optional<Float>> weights(List<double[]> samples) {
      return Map.of();
    }
  }

  /** Fine for rule 4: no floating point anywhere in the signatures. */
  public static final class DecimalMoney {
    BigDecimal[] prices;
    List<BigDecimal> marks;

    public Map<String, Optional<Long>> sizes(List<int[]> samples) {
      return Map.of();
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
