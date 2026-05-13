package com.example.mealprep.planner;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Planner-module repository isolation. Other modules must not import {@code
 * planner.domain.repository}; cross-module callers go through {@link
 * com.example.mealprep.planner.domain.service.PlanQueryService} (or the future {@code
 * PlannerService} arriving with 01j). Mirrors {@code RecipeBoundaryTest}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class PlannerBoundaryTest {

  @ArchTest
  static final ArchRule plannerReposAreInternalToPlanner =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.planner..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.planner.domain.repository..")
          .as(
              "planner repos are accessible only within the planner module —"
                  + " cross-module callers go through PlanQueryService.")
          .allowEmptyShould(true);
}
