package com.stocks.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.arch.fixtures.Violations;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/** Proves the §5.2 rules actually fire, on deliberate violations in test sources ({@link Violations}). */
class ArchitectureRulesSelfTest {

  static JavaClasses of(Class<?>... types) {
    return new ClassFileImporter().importClasses(types);
  }

  @Test
  void rule4CatchesDoubleFieldsParametersAndReturns() {
    EvaluationResult r = ArchitectureRules.noFloatingPoint("com.stocks.arch.fixtures..")
        .evaluate(of(Violations.FloatingMoney.class));
    assertThat(r.hasViolation()).isTrue();
    assertThat(r.getFailureReport().getDetails()).anySatisfy(d -> assertThat(d).contains("price"))
        .anySatisfy(d -> assertThat(d).contains("returns java.lang.Double"))
        .anySatisfy(d -> assertThat(d).contains("takes float"));
  }

  @Test
  void rule5CatchesSystemClockCalls() {
    EvaluationResult r = ArchitectureRules.noSystemClock().evaluate(of(Violations.SystemClock.class));
    assertThat(r.getFailureReport().getDetails()).hasSize(3);
  }

  @Test
  void rule6CatchesBigDecimalFromDouble() {
    assertThat(ArchitectureRules.noBigDecimalFromDouble().evaluate(of(Violations.DoubleDecimal.class))
        .getFailureReport().getDetails()).hasSize(2);
    assertThat(ArchitectureRules.noBigDecimalFromDouble().evaluate(of(Violations.Allowed.class)).hasViolation())
        .isFalse();
  }

  @Test
  void rule9CatchesRawJdbcTimeAccess() {
    EvaluationResult r = ArchitectureRules.jdbcTimeTypesOnlyInCommonJdbc().evaluate(of(Violations.RawJdbcTime.class));
    assertThat(r.hasViolation()).isTrue();
    assertThat(r.getFailureReport().getDetails()).anySatisfy(d -> assertThat(d).contains("getObject"))
        .anySatisfy(d -> assertThat(d).contains("getTimestamp"));
  }
}
