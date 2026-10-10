package com.stocks.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.stocks.arch.ArchitectureRules;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * §5.2 rules 4 and 8, scoped to {@code com.stocks.contract..} (WS1's test kit owns the global ArchUnit suite):
 * no floating point, and no dependency on any feature package.
 */
class ContractArchitectureTest {

  static final JavaClasses CONTRACT = new ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("com.stocks.contract");

  @Test
  void noDoubleOrFloat() {
    // §5.2 rule 4, with the arch rule's own type walk (arrays and generic arguments included)
    List<String> violations = new ArrayList<>();
    for (JavaClass c : CONTRACT) {
      for (JavaField f : c.getFields()) {
        ArchitectureRules.floatingPointIn(f.getType()).ifPresent(t -> violations.add(f.getFullName() + " is " + t));
      }
      for (JavaCodeUnit m : c.getCodeUnits()) {
        ArchitectureRules.floatingPointIn(m.getReturnType())
            .ifPresent(t -> violations.add(m.getFullName() + " returns " + t));
        for (JavaType p : m.getParameterTypes()) {
          ArchitectureRules.floatingPointIn(p).ifPresent(t -> violations.add(m.getFullName() + " takes " + t));
        }
      }
    }
    assertThat(violations).as("§5.2 rule 4: no floating point in com.stocks.contract..").isEmpty();
  }

  @Test
  void dependsOnlyOnCommonTheJdkAndAnnotationApis() {
    classes().that().resideInAPackage("com.stocks.contract..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "com.stocks.contract..", "com.stocks.common..", "java..",
            "com.fasterxml.jackson.annotation..", "jakarta.validation..", "io.swagger.v3.oas.annotations..",
            "org.springframework.security.core..", "io.modelcontextprotocol.common..")
        .check(CONTRACT);
  }
}
