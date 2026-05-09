package com.example.mealprep.nutrition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Nutrition-module repository isolation. Other modules must not import {@code
 * nutrition.domain.repository}. Cross-module callers go through {@code NutritionQueryService} /
 * {@code NutritionUpdateService}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class NutritionBoundaryTest {

  @ArchTest
  static final ArchRule nutritionReposAreInternalToNutrition =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.nutrition..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.nutrition.domain.repository..")
          .as(
              "nutrition repos are accessible only within the nutrition module —"
                  + " cross-module callers go through NutritionQueryService /"
                  + " NutritionUpdateService.")
          .allowEmptyShould(true);
}
