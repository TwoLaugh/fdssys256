package com.example.mealprep.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Core-module repository isolation. Other modules must not import {@code core.audit.repository},
 * {@code core.lock.repository}, etc. Cross-module callers go through {@code
 * DecisionLogQueryService} / {@code LockService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class CoreBoundaryTest {

  @ArchTest
  static final ArchRule coreReposAreInternalToCore =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.core..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.core..domain.repository..")
          .as(
              "core repos are accessible only within the core module —"
                  + " cross-module callers go through DecisionLogQueryService / LockService.")
          .allowEmptyShould(true);
}
