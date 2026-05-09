package com.example.mealprep.recipe;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Recipe-module repository isolation. Other modules must not import {@code
 * recipe.domain.repository}. Cross-module callers go through {@code RecipeQueryService} / {@code
 * RecipeUpdateService}.
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
}
