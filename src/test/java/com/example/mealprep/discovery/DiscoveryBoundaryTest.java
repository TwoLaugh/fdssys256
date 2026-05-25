package com.example.mealprep.discovery;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.domain.service.DiscoverySource;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
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

  /**
   * Carve-out for {@code DiscoveryJobTrigger}: a plain enum (no JPA annotations) that is part of
   * the PUBLISHED cross-module DTO contract — both {@code StartDiscoveryJobRequest} (the input the
   * whitelisted planner/recipe callers construct) and {@code DiscoveryJobDto} (the read shape they
   * receive) expose it. It happens to live in {@code domain.entity} ({@code DiscoveryJob} persists
   * it as an {@code @Enumerated} column) but it is not an aggregate entity; the rule's intent is
   * "no JPA aggregate roots cross the boundary", which this enum is not. The recipe-pool Tier-2
   * cold-start gate (planner {@code ColdStartGate}) is the first real cross-module caller and the
   * first to reference it. FLAGGED for human review: the cleaner long-term fix is to relocate this
   * enum to {@code discovery.api.dto} (it is a contract type), but that inverts the entity&rarr;api
   * layering for {@code DiscoveryJob}'s {@code @Enumerated} mapping, so it is left as a documented
   * carve-out rather than a riskier package move.
   */
  private static final DescribedPredicate<JavaClass> discoveryEntitiesExceptPublishedContractEnum =
      resideInAPackage("com.example.mealprep.discovery.domain.entity..")
          .and(
              not(
                  JavaClass.Predicates.equivalentTo(
                      com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.class)));

  @ArchTest
  static final ArchRule discoveryEntitiesAreInternalToDiscovery =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.example.mealprep.discovery..")
          .should()
          .dependOnClassesThat(discoveryEntitiesExceptPublishedContractEnum)
          .as(
              "discovery entities never cross module boundaries —"
                  + " cross-module data transfer is via DTOs in discovery.api.dto"
                  + " (DiscoveryJobTrigger excepted: it is a published DTO-contract enum).")
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

  /**
   * {@code DiscoveryService} is the public facade for the planner (cold-start via {@code
   * runJobSync}) and the recipe module (user-initiated via {@code startJob}) only. Per LLD line 619
   * / ticket invariants 20-21. Vacuously true today (no planner/recipe consumers wired yet); it
   * trips immediately if any other module (notification, feedback, …) pulls discovery in. {@code
   * DiscoveryQueryService} is deliberately NOT covered (admin/debug callers may live anywhere) per
   * ticket invariant 22.
   */
  @ArchTest
  static final ArchRule discoveryServiceInjectedOnlyByPlannerAndRecipe =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              "com.example.mealprep.discovery..",
              "com.example.mealprep.planner..",
              "com.example.mealprep.recipe..")
          .should()
          .dependOnClassesThat()
          .areAssignableTo(DiscoveryService.class)
          .as(
              "DiscoveryService is injected only by planner.. and recipe.. —"
                  + " the cold-start / user-initiated boundary per LLD line 619.")
          .allowEmptyShould(true);
}
