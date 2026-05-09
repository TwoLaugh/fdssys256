package com.example.mealprep.provisions;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Provisions-module repository isolation. Other modules must not import {@code
 * provisions.domain.repository}. Cross-module callers go through {@code ProvisionQueryService} /
 * {@code ProvisionUpdateService} (or the {@code ProvisionsModule} facade).
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ProvisionsBoundaryTest {

  @ArchTest
  static final ArchRule provisionsReposAreInternalToProvisions =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.provisions..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.provisions.domain.repository..")
          .as(
              "provisions repos are accessible only within the provisions module —"
                  + " cross-module callers go through ProvisionQueryService /"
                  + " ProvisionUpdateService.")
          .allowEmptyShould(true);
}
