package com.example.mealprep.household;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Household-module repository isolation. Other modules must not import {@code
 * household.domain.repository}. Cross-module callers go through {@code HouseholdQueryService} /
 * {@code HouseholdUpdateService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class HouseholdBoundaryTest {

  @ArchTest
  static final ArchRule householdReposAreInternalToHousehold =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.household..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.household.domain.repository..")
          .as(
              "household repos are accessible only within the household module —"
                  + " cross-module callers go through HouseholdQueryService /"
                  + " HouseholdUpdateService.")
          .allowEmptyShould(true);
}
