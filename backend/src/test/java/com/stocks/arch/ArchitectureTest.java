package com.stocks.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * §5.2 architecture rules on the production classes ({@code src/main}), run by {@code ./mvnw verify}. Rules that name
 * packages which do not exist yet (trading, mcp, …) pass vacuously until those packages land.
 */
class ArchitectureTest {

  static final JavaClasses MAIN = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("com.stocks");

  @Test
  void importsTheWholeApplication() {
    assertThat(MAIN.contain(com.stocks.StocksApplication.class)).isTrue();
    assertThat(MAIN.size()).isGreaterThan(200);
  }

  @Test
  void rule1_onlyTheEngineUsesTheWriters() {
    ArchitectureRules.onlyEngineUsesWriters().check(MAIN);
  }

  @Test
  void rule2_edgesDoNotReachTheEngine() {
    ArchitectureRules.edgesDoNotReachTheEngine().check(MAIN);
  }

  @Test
  void rule3_mcpDoesNotDependOnAdmin() {
    ArchitectureRules.mcpDoesNotDependOnAdmin().check(MAIN);
  }

  @Test
  void rule4_noFloatingPointInMoneyCode() {
    ArchitectureRules.noFloatingPoint(ArchitectureRules.FLOATING_POINT_FREE).check(MAIN);
  }

  @Test
  void rule5_noSystemClockOutsideCommonTime() {
    ArchitectureRules.noSystemClock().check(MAIN);
  }

  @Test
  void rule6_noBigDecimalFromDouble() {
    ArchitectureRules.noBigDecimalFromDouble().check(MAIN);
  }

  @Test
  void rule7_noCyclesBetweenTopLevelPackages() {
    ArchitectureRules.noPackageCycles().check(MAIN);
  }

  @Test
  void rule8_commonDependsOnNoFeature() {
    ArchitectureRules.commonIsSelfContained().check(MAIN);
  }

  @Test
  void rule9_jdbcTimeTypesOnlyInCommonJdbc() {
    ArchitectureRules.jdbcTimeTypesOnlyInCommonJdbc().check(MAIN);
  }

  @Test
  void section25_noSpringScheduled() {
    ArchitectureRules.noSpringScheduled().check(MAIN);
  }

  /** §8: {@code trading_day} is read only by {@code com.stocks.calendar}; everyone else asks MarketCalendar. */
  @Test
  void section8_tradingDayTableOnlyInCalendar() throws IOException {
    Path main = Path.of("src", "main", "java");
    Path calendar = main.resolve(Path.of("com", "stocks", "calendar"));
    List<Path> offenders;
    try (Stream<Path> files = Files.walk(main)) {
      offenders = files.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.startsWith(calendar))
          .filter(p -> {
            try {
              return Files.readString(p).toLowerCase(java.util.Locale.ROOT).contains("trading_day");
            } catch (IOException e) {
              throw new java.io.UncheckedIOException(e);
            }
          })
          .toList();
    }
    assertThat(offenders).as("§8: only com.stocks.calendar may read trading_day").isEmpty();
  }
}
