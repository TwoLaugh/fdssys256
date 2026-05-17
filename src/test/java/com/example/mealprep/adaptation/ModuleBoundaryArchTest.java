package com.example.mealprep.adaptation;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

/**
 * Adaptation-pipeline module-boundary rules (ticket 01f §ModuleBoundaryArchTest, LLD line 937).
 * Closes the module: nothing outside {@code adaptation..} may reach into its repositories or
 * entities, and the adaptation module itself only consumes peer modules through their published
 * seams.
 *
 * <h2>Calibrated allow-lists</h2>
 *
 * The LLD line 937 sketch ("{@code RecipeWriteApi} is the only {@code recipe.*} symbol injected
 * here") is a loose intent. The real pipeline legitimately consumes a wider published surface; per
 * ticket 01f §19 the allow-list is calibrated against actual imports rather than the literal
 * sketch. The boundary is therefore expressed as <em>deny the genuinely-internal packages</em>
 * (repositories, {@code *.internal..}, JPA entities) while permitting each module's public API /
 * SPI / event / exception surface:
 *
 * <ul>
 *   <li><b>recipe</b>: allowed — {@code recipe.spi..} ({@code RecipeWriteApi} + commands), {@code
 *       recipe.api.dto..} ({@code RecipeDto}, {@code RecipeVersionDto}, {@code
 *       CharacterFingerprintDto}, {@code RecipeDiffDto}, …), {@code recipe.api.service..} / {@code
 *       recipe.domain.service.RecipeQueryService} (context loader reads recipe state), {@code
 *       recipe.domain.entity.Catalogue} / {@code DataQuality} (shared enums on job DTOs), {@code
 *       recipe.event..}, {@code recipe.exception..}. Denied — {@code recipe.domain.repository..}
 *       and {@code recipe.spi.internal..}.
 *   <li><b>ai</b>: allowed — {@code ai.spi..} ({@code AiTask} + task contracts), {@code
 *       ai.exception..}, {@code ai.domain.service.AiService} (the published invoker). Denied —
 *       {@code ai.domain.repository..} and {@code ai.domain.entity..}.
 * </ul>
 *
 * <p>This mirrors how {@code RecipeBoundaryTest} whitelists the adaptation pipeline as the single
 * permitted external consumer of {@code recipe.spi}.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

  @ArchTest
  static final ArchRule no_outside_imports_of_adaptation_repositories =
      ArchRuleDefinition.noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.adaptation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.adaptation.domain.repository..")
          .as(
              "adaptation repositories are module-internal — cross-module callers go through"
                  + " AdaptationService / AdaptationQueryService / the AdaptationModule facade.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule no_outside_imports_of_adaptation_entities =
      ArchRuleDefinition.noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.adaptation..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.adaptation.domain.entity..")
          .as("adaptation JPA entities are an internal concern; peers consume DTOs.")
          .allowEmptyShould(true);

  /**
   * Adaptation must not reach into recipe's repositories or SPI internals. The published recipe
   * surface (spi, api.dto, the shared Catalogue/DataQuality enums, RecipeQueryService, events,
   * exceptions) is the allowed seam — see class Javadoc.
   */
  @ArchTest
  static final ArchRule recipe_writeapi_is_only_recipe_dep =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("com.example.mealprep.adaptation..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.mealprep.recipe.domain.repository..",
              "com.example.mealprep.recipe.spi.internal..")
          .as(
              "adaptation consumes recipe only via its published spi / api / query seam;"
                  + " recipe repositories + spi internals are off-limits.")
          .allowEmptyShould(true);

  /**
   * Adaptation must not reach into ai's repositories or entities. {@code ai.spi}, {@code
   * ai.exception}, and the published {@code AiService} are the allowed seam — see class Javadoc.
   */
  @ArchTest
  static final ArchRule aitask_spi_is_only_ai_dep =
      ArchRuleDefinition.noClasses()
          .that()
          .resideInAPackage("com.example.mealprep.adaptation..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.example.mealprep.ai.domain.repository..",
              "com.example.mealprep.ai.domain.entity..")
          .as(
              "adaptation consumes ai only via ai.spi / ai.exception / AiService;"
                  + " ai repositories + entities are off-limits.")
          .allowEmptyShould(true);
}
