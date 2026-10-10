package com.stocks.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.Date;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The §5.2 architecture rules as ArchUnit rules. {@link ArchitectureTest} checks them against {@code src/main}; the
 * package-parameterized factories also let {@code ArchitectureRulesSelfTest} prove each rule catches a violation.
 */
public final class ArchitectureRules {

  private ArchitectureRules() {}

  public static final String[] FLOATING_POINT_FREE = {
      "com.stocks.trading..", "com.stocks.portfolio..", "com.stocks.league..", "com.stocks.contract..",
      "com.stocks.common.money.."};

  /** Rule 1: only the engine, account and corporate-action code may use the engine writers. */
  public static ArchRule onlyEngineUsesWriters() {
    return noClasses()
        .that().resideOutsideOfPackages("com.stocks.trading.engine..", "com.stocks.trading.account..",
            "com.stocks.trading.corpactions..")
        .should().dependOnClassesThat().resideInAPackage("com.stocks.trading.engine.write..")
        .because("§5.2 rule 1: writers run only inside engine command handlers")
        .allowEmptyShould(true);
  }

  /**
   * Rule 2: web, MCP, realtime and admin code use the contract interfaces, never the engine (other than the
   * {@code EngineCommand} records submitted through services) or a writer.
   */
  public static ArchRule edgesDoNotReachTheEngine() {
    DescribedPredicate<JavaClass> engineInternals = new DescribedPredicate<>("engine internals or *Writer") {
      @Override
      public boolean test(JavaClass c) {
        boolean writer = c.getPackageName().startsWith("com.stocks.trading") && c.getSimpleName().endsWith("Writer");
        boolean engine = c.getPackageName().contains(".engine") && c.getPackageName().startsWith("com.stocks.")
            && !isEngineCommand(c);
        return writer || engine;
      }
    };
    return noClasses()
        .that().resideInAnyPackage("..web..", "com.stocks.mcp..", "com.stocks.realtime..", "com.stocks.admin..")
        .should().dependOnClassesThat(engineInternals)
        .because("§5.2 rule 2: edges talk to com.stocks.contract.. interfaces")
        .allowEmptyShould(true);
  }

  private static boolean isEngineCommand(JavaClass c) {
    if (c.getSimpleName().equals("EngineCommand")) {
      return true;
    }
    if (c.getEnclosingClass().map(e -> e.getSimpleName().equals("EngineCommand")).orElse(false)) {
      return true;
    }
    return c.getAllRawInterfaces().stream().anyMatch(i -> i.getSimpleName().equals("EngineCommand"));
  }

  /** Rule 3: MCP never reaches admin (there are no admin tools, §17.4). */
  public static ArchRule mcpDoesNotDependOnAdmin() {
    return noClasses().that().resideInAPackage("com.stocks.mcp..")
        .should().dependOnClassesThat().resideInAPackage("com.stocks.admin..")
        .because("§5.2 rule 3 / §17.4 layer 4")
        .allowEmptyShould(true);
  }

  /** Rule 4: no {@code double}/{@code float}/{@code Double}/{@code Float} field, parameter or return type. */
  public static ArchRule noFloatingPoint(String... packages) {
    Set<String> banned = Set.of("double", "float", "java.lang.Double", "java.lang.Float");
    ArchCondition<JavaClass> noFloats = new ArchCondition<>("not use double, float, Double or Float") {
      @Override
      public void check(JavaClass c, ConditionEvents events) {
        for (JavaField f : c.getFields()) {
          if (banned.contains(f.getRawType().getName())) {
            events.add(SimpleConditionEvent.violated(f, f.getFullName() + " is " + f.getRawType().getName()));
          }
        }
        for (JavaCodeUnit m : c.getCodeUnits()) {
          if (banned.contains(m.getRawReturnType().getName())) {
            events.add(SimpleConditionEvent.violated(m, m.getFullName() + " returns " + m.getRawReturnType().getName()));
          }
          for (JavaClass p : m.getRawParameterTypes()) {
            if (banned.contains(p.getName())) {
              events.add(SimpleConditionEvent.violated(m, m.getFullName() + " takes " + p.getName()));
            }
          }
        }
      }
    };
    return classes().that().resideInAnyPackage(packages).should(noFloats)
        .because("§5.2 rule 4 / D4: money is BigDecimal")
        .allowEmptyShould(true);
  }

  /** Rule 5: "now" comes from TimeSource; the system clock is read only in {@code com.stocks.common.time}. */
  public static ArchRule noSystemClock() {
    return noClasses().that().resideOutsideOfPackage("com.stocks.common.time..")
        .should().callMethod(Instant.class, "now")
        .orShould().callMethod(LocalDate.class, "now")
        .orShould().callMethod(LocalDate.class, "now", ZoneId.class)
        .orShould().callMethod(LocalDateTime.class, "now")
        .orShould().callMethod(LocalDateTime.class, "now", ZoneId.class)
        .orShould().callMethod(ZonedDateTime.class, "now")
        .orShould().callMethod(ZonedDateTime.class, "now", ZoneId.class)
        .orShould().callMethod(OffsetDateTime.class, "now")
        .orShould().callMethod(OffsetDateTime.class, "now", ZoneId.class)
        .orShould().callMethod(LocalTime.class, "now")
        .orShould().callMethod(LocalTime.class, "now", ZoneId.class)
        .orShould().callMethod(Clock.class, "systemUTC")
        .orShould().callMethod(Clock.class, "systemDefaultZone")
        .orShould().callMethod(System.class, "currentTimeMillis")
        .orShould().callMethod(ZoneId.class, "systemDefault")
        .orShould().callConstructor(Date.class)
        .because("§5.2 rule 5 / §6.4: inject TimeSource");
  }

  /** Rule 6: no BigDecimal from a double, anywhere. */
  public static ArchRule noBigDecimalFromDouble() {
    return noClasses()
        .should().callConstructor(BigDecimal.class, double.class)
        .orShould().callConstructor(BigDecimal.class, double.class, MathContext.class)
        .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
        .because("§5.2 rule 6: decimals never pass through double");
  }

  /** Rule 7: no cycles between top-level packages (cross-feature calls go through com.stocks.contract). */
  public static ArchRule noPackageCycles() {
    return slices().matching("com.stocks.(*)..").should().beFreeOfCycles()
        .because("§5.2 rule 7");
  }

  /** Rule 8 (common half; the contract half is ContractArchitectureTest): common depends on nothing in com.stocks. */
  public static ArchRule commonIsSelfContained() {
    return noClasses().that().resideInAPackage("com.stocks.common..")
        .should().dependOnClassesThat(resideInAPackage("com.stocks..").and(not(resideInAPackage("com.stocks.common.."))))
        .because("§5.2 rule 8");
  }

  /** Rule 9: timestamps cross JDBC only through {@code common.jdbc.Jdbc} (no raw Instant, §8 typing rules). */
  public static ArchRule jdbcTimeTypesOnlyInCommonJdbc() {
    return noClasses().that().resideOutsideOfPackage("com.stocks.common.jdbc..")
        .should().callMethod(ResultSet.class, "getObject", String.class, Class.class)
        .orShould().callMethod(ResultSet.class, "getObject", int.class, Class.class)
        .orShould().callMethod(ResultSet.class, "getTimestamp", String.class)
        .orShould().callMethod(ResultSet.class, "getTimestamp", int.class)
        .orShould().callMethod(ResultSet.class, "getTimestamp", String.class, Calendar.class)
        .orShould().callMethod(ResultSet.class, "getTimestamp", int.class, Calendar.class)
        .orShould().dependOnClassesThat().areAssignableTo(Timestamp.class)
        .because("§5.2 rule 9: bind Jdbc.ts(instant), read Jdbc.instant(rs, col)");
  }

  /** §25: no workstream uses {@code @Scheduled}; jobs are ScheduledJob/PeriodicTask beans run by JobScheduler. */
  public static ArchRule noSpringScheduled() {
    return methods().that().areDeclaredInClassesThat(resideInAnyPackage("com.stocks.."))
        .should().notBeAnnotatedWith(Scheduled.class)
        .because("§25: implement ScheduledJob or PeriodicTask instead")
        .allowEmptyShould(true);
  }
}
