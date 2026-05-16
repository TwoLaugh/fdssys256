package com.example.mealprep.discovery;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Discovery-module isolation rules. Mirrors {@code RecipeBoundaryTest} / {@code
 * PreferenceBoundaryTest}.
 *
 * <p>Two rules ship in 01a:
 *
 * <ul>
 *   <li>Other modules must not import {@code discovery.domain.repository..} — repositories are
 *       package-private, this is the ArchUnit backstop.
 *   <li>Other modules must not import {@code discovery.domain.entity..} — entities never cross
 *       module boundaries; cross-module data transfer is via DTOs.
 * </ul>
 *
 * <p>The {@code DiscoverySource} SPI rule (only inside {@code discovery.source..}) lands with
 * discovery-01c when the SPI ships; the {@code DiscoveryService injected only by planner+recipe}
 * rule lands with discovery-01b when the interfaces gain methods.
 */
@AnalyzeClasses(
    packages = "com.example.mealprep",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class DiscoveryBoundaryTest {

  @ArchTest
  static final ArchRule discoveryReposAreInternalToDiscovery =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.discovery..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.discovery.domain.repository..")
          .as(
              "discovery repos are accessible only within the discovery module —"
                  + " cross-module callers go through DiscoveryQueryService /"
                  + " DiscoveryService.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule discoveryEntitiesAreInternalToDiscovery =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.discovery..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.example.mealprep.discovery.domain.entity..")
          .as(
              "discovery entities never cross module boundaries —"
                  + " cross-module data transfer is via DTOs in discovery.api.dto.")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule discoverySourceImplsLiveInSourcePackage =
      classes()
          .that()
          .implement(DiscoverySource.class)
          .should()
          .resideInAPackage("com.example.mealprep.discovery.source..")
          .as(
              "DiscoverySource implementations live exclusively in"
                  + " com.example.mealprep.discovery.source.. — the source/ package is"
                  + " a hard pocket per LLD line 400.")
          .allowEmptyShould(true);
}
