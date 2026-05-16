package com.example.mealprep.recipe;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Recipe-module repository + SPI isolation. Other modules must not import {@code
 * recipe.domain.repository} or {@code recipe.spi}. Cross-module callers go through {@code
 * RecipeQueryService} / {@code RecipeUpdateService}; the Adaptation Pipeline (separate module, not
 * yet built) is the single intended external consumer of {@code RecipeWriteApi}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class RecipeBoundaryTest {

  @ArchTest
  static final ArchRule recipeReposAreInternalToRecipe =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.recipe..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.recipe.domain.repository..")
          .as(
              "recipe repos are accessible only within the recipe module —"
                  + " cross-module callers go through RecipeQueryService /"
                  + " RecipeUpdateService.")
          .allowEmptyShould(true);

  /**
   * Per LLD line 824 — the {@code RecipeWriteApi} SPI is internal to the recipe module + the
   * Adaptation Pipeline module. The adaptation pipeline ships in wave 3 (adaptation-pipeline-01c
   * onwards) and consumes {@code RecipeWriteApi.saveAdaptedVersion} / {@code saveAdaptedBranch}
   * from its {@code RebaseOrchestrator}. All other modules must go through {@code
   * RecipeQueryService} / {@code RecipeUpdateService}.
   */
  @ArchTest
  static final ArchRule recipeWriteApiNotImportedByOtherModules =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "com.example.mealprep.recipe..", "com.example.mealprep.adaptation..", "..test..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.recipe.spi..")
          .as(
              "the RecipeWriteApi SPI (and its commands) is consumed only by the recipe"
                  + " module + the Adaptation Pipeline; other modules must go through"
                  + " RecipeQueryService / RecipeUpdateService.")
          .allowEmptyShould(true);
}
