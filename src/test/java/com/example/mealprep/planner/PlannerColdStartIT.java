package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.discovery.api.dto.DiscoveryCandidate;
import com.example.mealprep.discovery.api.dto.DiscoveryQuery;
import com.example.mealprep.discovery.api.dto.ParsedRecipe;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.PlannerSlotEntryDto;
import com.example.mealprep.household.api.dto.SlotConfigurationPlannerViewDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdMergeService;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.domain.entity.MealSlot;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.composer.PlanComposer;
import com.example.mealprep.recipe.domain.service.RecipeQueryService;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testcontainers IT for the recipe-pool Tier-2 cold-start gate. A fresh user/household with an
 * EMPTY catalogue drives {@link PlanComposer#compose}; the gate fires (pool 0 &lt; threshold),
 * invokes the discovery module via {@code runJobSync}, the deterministic test {@link
 * com.example.mealprep.discovery.domain.service.DiscoverySource} imports recipes into the SYSTEM
 * catalogue, and the re-read Stage-A pool produces a plan whose slots schedule those seed recipes.
 *
 * <p>Asserts the brief's DoD: plan is {@code GENERATED}, {@code coldStart == true}, slots are
 * non-empty and reference the imported SYSTEM recipes.
 *
 * <p>The real {@code DiscoveryJobRunner} is LIVE here (async). A deterministic in-test {@code
 * DiscoverySource} bean stands in for the non-deterministic web/Google sources (the same pattern
 * the {@code e2e}-profile {@code E2eSeedDiscoverySource} uses in the stack), and {@code
 * mealprep.planner.cold-start.source-keys} pins the gate to it. Only the cross-module read surfaces
 * the composer needs (household roster + slot-config) and the LLM stages are mocked; the recipe
 * import + pool read + beam search + persistence are all the real beans.
 */
@SpringBootTest
@Import({TestContainersConfig.class, PlannerColdStartIT.ColdStartSourceConfig.class})
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "mealprep.planner.cold-start.enabled=true",
      "mealprep.planner.cold-start.slot-kind-multiplier=3",
      "mealprep.planner.cold-start.requested-count=20",
      "mealprep.planner.cold-start.timeout=PT30S",
      "mealprep.planner.cold-start.source-keys=coldstart_seed"
    })
class PlannerColdStartIT {

  private static final String SEED_KEY = "coldstart_seed";
  private static final LocalDate WEEK =
      LocalDate.now().plusYears(41).with(java.time.DayOfWeek.MONDAY);

  @Autowired private PlanComposer composer;
  @Autowired private PlanRepository planRepository;
  @Autowired private RecipeQueryService recipeQueryService;
  @Autowired private DiscoverySourceRepository sourceRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  // The discovery runner's AI candidate-filter (DISCOVERY_FILTERING) gates every candidate; the
  // test-profile TestAiService stub starts with no canned response and would DROP all candidates.
  // Register an accept-all response so the deterministic seed candidates survive the filter — the
  // real prod parity is that the cold-start path needs a DISCOVERY_FILTERING response available
  // (flagged in the report for the e2e stack).
  @Autowired private com.example.mealprep.ai.testing.TestAiService testAiService;

  @MockBean private HouseholdQueryService householdQueryService;
  // HouseholdServiceImpl implements all three interfaces; mock the siblings too (multi-interface
  // @MockBean eviction — same retro as the other planner ITs).
  @MockBean private HouseholdUpdateService householdUpdateService;
  @MockBean private HouseholdMergeService householdMergeService;

  // Deterministic LLM stages so the composer run is repeatable (same as
  // CatalogueRecipePoolSourceIT).
  @MockBean
  private com.example.mealprep.planner.domain.service.internal.stagec.StageCInvoker stageCInvoker;

  @MockBean
  private com.example.mealprep.planner.domain.service.internal.stagec.Phase2Augmenter
      phase2Augmenter;

  @MockBean private AdaptationService adaptationService;
  @MockBean private AdaptationQueryService adaptationQueryService;

  private TransactionTemplate tx() {
    return new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM planner_scheduled_recipes");
    jdbcTemplate.update("DELETE FROM planner_meal_slots");
    jdbcTemplate.update("DELETE FROM planner_days");
    jdbcTemplate.update("DELETE FROM planner_plans");
    jdbcTemplate.update("DELETE FROM decision_log");
    jdbcTemplate.update("DELETE FROM discovery_scrape_log");
    jdbcTemplate.update("DELETE FROM discovery_jobs");
    jdbcTemplate.update("DELETE FROM recipe_imports");
    jdbcTemplate.update("DELETE FROM recipe_tags");
    jdbcTemplate.update("DELETE FROM recipe_metadata");
    jdbcTemplate.update("DELETE FROM recipe_method_steps");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
    // Remove only the deterministic seed source row (leave the R__-seeded curated rows alone).
    jdbcTemplate.update("DELETE FROM discovery_sources WHERE source_key = ?", SEED_KEY);
  }

  private void seedSeedSourceRow() {
    DiscoverySource row = DiscoveryTestData.sampleSource(SEED_KEY);
    row.setEnabled(true);
    row.setRespectRobotsTxt(false);
    row.setRequestsPerMinute(600);
    sourceRepository.saveAndFlush(row);
  }

  private void stubHousehold(UUID householdId, UUID userId) {
    HouseholdMemberDto member =
        new HouseholdMemberDto(
            UUID.randomUUID(),
            householdId,
            userId,
            HouseholdRole.primary,
            "owner",
            0,
            Instant.now(),
            0L);
    when(householdQueryService.getById(eq(householdId)))
        .thenReturn(
            Optional.of(
                new HouseholdDto(householdId, "h", userId, List.of(member), Instant.now(), 0L)));
  }

  private void stubSlotConfig(UUID householdId, UUID userId) {
    PlannerSlotEntryDto dinner =
        new PlannerSlotEntryDto(
            "dinner",
            com.example.mealprep.household.domain.entity.SlotKind.dinner,
            true,
            1,
            60,
            null,
            null);
    when(householdQueryService.getSlotConfigurationPlannerView(householdId))
        .thenReturn(
            new SlotConfigurationPlannerViewDto(
                householdId,
                List.of(dinner),
                List.of(userId),
                List.of(userId),
                null,
                null,
                Instant.now()));
    when(householdQueryService.getSettings(eq(householdId), any())).thenReturn(Optional.empty());
  }

  @Test
  void emptyCatalogue_coldStartGate_fillsSystemCatalogue_andSchedulesSeedRecipes() {
    UUID household = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    seedSeedSourceRow();
    // Accept-all AI candidate filter so the deterministic seed candidates survive the runner's
    // DISCOVERY_FILTERING gate and get imported. registerJson deserialises through the real
    // ObjectMapper into the (package-private) CandidateFilterResult, exercising the wire contract.
    testAiService.registerJson(
        com.example.mealprep.ai.spi.TaskType.DISCOVERY_FILTERING,
        "{\"relevant\":true,\"confidence\":0.95,\"reason\":\"relevant\"}");
    stubHousehold(household, userId);
    stubSlotConfig(household, userId);
    when(stageCInvoker.pickOne(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.StageCResult(
                0,
                "picked",
                com.example.mealprep.planner.domain.entity.AugmentationSource.LLM,
                false));
    when(phase2Augmenter.augment(any(), any(), any(), any()))
        .thenReturn(
            new com.example.mealprep.planner.api.dto.AugmentationResult(
                List.of(), List.of(), List.of()));

    // Pre-condition: the SYSTEM catalogue is empty for this fresh user.
    assertThat(recipeQueryService.findPlannableCandidates(userId, 100)).isEmpty();

    GeneratePlanRequest request = new GeneratePlanRequest(household, WEEK, false);
    UUID planId = composer.compose(request, userId, null);

    assertThat(planId).isNotNull();

    // The cold-start gate filled the SYSTEM catalogue. runJobSync blocked on the runner's terminal
    // waiter, so the async runner's imports are committed + visible by the time compose() returned;
    // a short defensive poll absorbs any cross-thread commit-visibility lag.
    List<UUID> systemRecipeIds = pollSystemCatalogueNonEmpty(userId, Duration.ofSeconds(15));
    assertThat(systemRecipeIds).isNotEmpty();

    tx().executeWithoutResult(
            t -> {
              Plan plan = planRepository.findById(planId).orElseThrow();
              assertThat(plan.getStatus()).isEqualTo(PlanStatus.GENERATED);
              // The DoD assertion: the plan is flagged cold-start.
              assertThat(plan.isColdStart()).isTrue();

              List<MealSlot> slots =
                  plan.getDays().stream().flatMap(d -> d.getSlots().stream()).toList();
              // One DINNER slot per day across the 7-day week.
              assertThat(slots).hasSize(7);
              // Slots are non-empty and schedule the cold-start-imported SYSTEM recipes.
              assertThat(slots)
                  .allSatisfy(
                      slot -> {
                        ScheduledRecipe sr = slot.getScheduledRecipe();
                        assertThat(sr).isNotNull();
                        assertThat(sr.getRecipeId()).isNotNull();
                        assertThat(systemRecipeIds).contains(sr.getRecipeId());
                      });
            });
  }

  /** Poll the SYSTEM catalogue until it has plannable candidates for {@code userId}. */
  private List<UUID> pollSystemCatalogueNonEmpty(UUID userId, Duration atMost) {
    long deadline = System.nanoTime() + atMost.toNanos();
    List<UUID> ids = List.of();
    while (System.nanoTime() < deadline) {
      ids =
          recipeQueryService.findPlannableCandidates(userId, 100).stream()
              .map(com.example.mealprep.recipe.api.dto.RecipeDto::id)
              .toList();
      if (!ids.isEmpty()) {
        return ids;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return ids;
  }

  /**
   * Deterministic discovery source for the cold-start gate. Yields a fixed set of parseable,
   * mappable {@link ParsedRecipe}s with no network/credentials — the in-test analogue of the
   * {@code @Profile("e2e")} {@code E2eSeedDiscoverySource}. Anonymous impl in the test source set,
   * excluded from {@code DiscoveryBoundaryTest}'s source-package rule (DoNotIncludeTests).
   */
  @TestConfiguration
  static class ColdStartSourceConfig {

    @Bean
    com.example.mealprep.discovery.domain.service.DiscoverySource coldStartSeedSource() {
      return new com.example.mealprep.discovery.domain.service.DiscoverySource() {
        @Override
        public String key() {
          return SEED_KEY;
        }

        @Override
        public DiscoverySourceKind kind() {
          return DiscoverySourceKind.SITEMAP;
        }

        @Override
        public List<DiscoveryCandidate> search(DiscoveryQuery query) {
          List<DiscoveryCandidate> out = new ArrayList<>();
          int cap = Math.max(1, query.maxResults());
          for (int i = 0; i < Math.min(10, cap); i++) {
            out.add(
                new DiscoveryCandidate(
                    SEED_KEY,
                    "https://coldstart.seed.local/dinner/" + i,
                    "Dinner " + i,
                    "d",
                    java.util.Map.of()));
          }
          return out;
        }

        @Override
        public ParsedRecipe fetchRecipe(DiscoveryCandidate candidate) {
          String url = candidate.candidateUrl();
          String n = url.substring(url.lastIndexOf('/') + 1);
          // No ingredients: the discovery→recipe import path persists recipe_ingredients with a
          // NULL ingredient_mapping_key, which violates the NOT-NULL column (a PRE-EXISTING import
          // defect, flagged for follow-up — the discovery pipeline carries no mapping key on
          // ImportedRecipeData.ImportedIngredient). The cold-start gate + plan-scheduling proof
          // does not depend on ingredients; the per-recipe distinct method instruction keeps the
          // content fingerprint unique so each recipe imports rather than dedup-collapsing.
          return new ParsedRecipe(
              url,
              "ColdStart " + n,
              "deterministic cold-start seed",
              List.of(),
              List.of(new ParsedRecipe.ParsedMethodStep(1, "Cook and serve recipe " + n + ".", 20)),
              new ParsedRecipe.ParsedRecipeMetadata(
                  2, 10, 15, 25, List.of(), "International", List.of("dinner")),
              "seed",
              new BigDecimal("0.95"));
        }
      };
    }
  }
}
