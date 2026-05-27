package com.example.mealprep.adaptation.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.testdata.HardConstraintsTestData;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.event.RecipeCreatedEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-Postgres IT for the Trigger-1 cost-discipline gate on {@link AdaptationImportListener}
 * ({@code tickets/adaptation/02b-trigger1-cost-discipline.md}). Lives in the listener's package so
 * it can drive the (package-private) {@code decideAndEnqueue} method directly against the REAL
 * cross-module beans ({@code RecipeQueryService}, {@code HardConstraintFilterService}, {@code
 * NutritionQueryService}) and the REAL {@code AdaptationServiceImpl.enqueueImportJob} write path —
 * the {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW} entry point is
 * awkward to drive deterministically (same rationale as {@code AdaptationDataModelFilterIT}).
 *
 * <p>Proves the three branches by inspecting the persisted {@code adaptation_jobs} rows:
 *
 * <ul>
 *   <li>clean USER_VERIFIED (no conflicting ingredient, no nutrition) → NO job enqueued;
 *   <li>USER_VERIFIED whose ingredient violates a seeded {@code peanut} allergy → exactly ONE ASYNC
 *       job;
 *   <li>IMPORTED / WEB_DISCOVERED → exactly ONE BATCH job that stays PENDING (no JobReadyEvent → no
 *       immediate worker pickup; only the daily orchestrator would process it).
 * </ul>
 *
 * <p>Recipe rows are seeded via {@link JdbcTemplate} (mirroring {@code AdaptationDataModelFilterIT}
 * / {@code RecipeAffectedSetQueriesIT}); the hard-constraints aggregate via its repository + {@link
 * HardConstraintsTestData}. We assert on the IMMUTABLE {@code priority} / {@code source} / {@code
 * recipeId} of the persisted rows so the assertion is robust even if the async worker (for the
 * ASYNC case) flips the row's status concurrently.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationImportGatingIT {

  @Autowired private AdaptationImportListener listener;
  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private HardConstraintsRepository hardConstraintsRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_traces");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
    jdbcTemplate.update("DELETE FROM recipe_ingredients");
    jdbcTemplate.update("UPDATE recipe_recipes SET current_branch_id = NULL");
    jdbcTemplate.update("DELETE FROM recipe_versions");
    jdbcTemplate.update("DELETE FROM recipe_branches");
    jdbcTemplate.update("DELETE FROM recipe_recipes");
    hardConstraintsRepository.deleteAllInBatch();
  }

  @Test
  void cleanUserVerifiedCreate_noConflict_enqueuesNoJob() {
    UUID userId = UUID.randomUUID();
    // A clean recipe (no allergen, no nutrition) and NO hard-constraints aggregate at all → the
    // single-recipe pre-filter finds no conflict → SKIP.
    UUID recipeId = seedActiveUserRecipe(userId, "rice", "chicken");

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).isEmpty();
    assertThat(jobsFor(recipeId)).isEmpty();
  }

  @Test
  void userVerifiedCreate_violatingHardConstraint_enqueuesExactlyOneAsyncJob() {
    UUID userId = UUID.randomUUID();
    // Direct "peanut" allergy — the simplest real violation family (matches directAllergies first,
    // no derivative lookup); mirrors AdaptationDataModelFilterIT.
    hardConstraintsRepository.saveAndFlush(
        HardConstraintsTestData.hardConstraints()
            .withUserId(userId)
            .withAllergies("peanut")
            .build());
    UUID recipeId = seedActiveUserRecipe(userId, "peanut", "rice");

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.USER, DataQuality.USER_VERIFIED));

    assertThat(result).isPresent();
    List<AdaptationJob> jobs = jobsFor(recipeId);
    assertThat(jobs).hasSize(1);
    AdaptationJob job = jobs.get(0);
    assertThat(job.getId()).isEqualTo(result.get());
    assertThat(job.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(job.getPriority()).isEqualTo(JobPriority.ASYNC);
    assertThat(job.getRecipeId()).isEqualTo(recipeId);
  }

  @Test
  void importedCreate_enqueuesExactlyOneBatchJob_thatStaysPendingWithoutImmediateProcessing() {
    UUID userId = UUID.randomUUID();
    // Bulk-origin: an IMPORTED recipe goes to BATCH regardless of conflict — no pre-filter, no
    // per-recipe immediate LLM fan-out. (No conflicting ingredient seeded on purpose; the BATCH
    // route does not consult the pre-filter at all.)
    UUID recipeId = seedActiveUserRecipe(userId, "rice", "chicken");

    Optional<UUID> result =
        listener.decideAndEnqueue(event(recipeId, userId, Catalogue.USER, DataQuality.IMPORTED));

    assertThat(result).isPresent();
    List<AdaptationJob> jobs = jobsFor(recipeId);
    assertThat(jobs).hasSize(1);
    AdaptationJob job = jobs.get(0);
    assertThat(job.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(job.getPriority()).isEqualTo(JobPriority.BATCH);
    // No JobReadyEvent is published for BATCH → the @Async worker never picks it up; only the daily
    // BatchJobOrchestrator (which does not run inside this test) would. So it must still be
    // PENDING.
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
  }

  @Test
  void webDiscoveredCreate_enqueuesExactlyOneBatchJob() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = seedActiveUserRecipe(userId, "rice");

    Optional<UUID> result =
        listener.decideAndEnqueue(
            event(recipeId, userId, Catalogue.SYSTEM, DataQuality.WEB_DISCOVERED));

    assertThat(result).isPresent();
    List<AdaptationJob> jobs = jobsFor(recipeId);
    assertThat(jobs).hasSize(1);
    assertThat(jobs.get(0).getPriority()).isEqualTo(JobPriority.BATCH);
    assertThat(jobs.get(0).getStatus()).isEqualTo(JobStatus.PENDING);
  }

  // ---------------- helpers ----------------

  private List<AdaptationJob> jobsFor(UUID recipeId) {
    return jobRepository
        .findByRecipeIdOrderByEnqueuedAtDesc(recipeId, PageRequest.of(0, 50))
        .getContent();
  }

  private static RecipeCreatedEvent event(
      UUID recipeId, UUID userId, Catalogue catalogue, DataQuality dataQuality) {
    return new RecipeCreatedEvent(
        recipeId, catalogue, userId, dataQuality, UUID.randomUUID(), Instant.now());
  }

  // ---------------- recipe seed helpers (mirror AdaptationDataModelFilterIT) ----------------

  private UUID seedActiveUserRecipe(UUID userId, String... ingredientKeys) {
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    seedRecipe(recipeId, userId);
    seedBranch(branchId, recipeId);
    UUID versionId = UUID.randomUUID();
    seedVersion(versionId, recipeId, branchId);
    int line = 0;
    for (String key : ingredientKeys) {
      seedIngredient(versionId, line++, key);
    }
    return recipeId;
  }

  private void seedRecipe(UUID id, UUID userId) {
    jdbcTemplate.update(
        "INSERT INTO recipe_recipes (id, user_id, catalogue, name, current_version,"
            + " current_branch_id, data_quality, nutrition_status, archived_at, deleted_at,"
            + " optimistic_version, created_at, updated_at)"
            + " VALUES (?, ?, 'USER', ?, 1, NULL, 'USER_VERIFIED', 'PENDING', NULL, NULL, 0, now(),"
            + " now())",
        id,
        userId,
        "Recipe " + id);
  }

  private void seedBranch(UUID id, UUID recipeId) {
    jdbcTemplate.update(
        "INSERT INTO recipe_branches (id, recipe_id, name, current_version, divergence_score,"
            + " created_at, created_by_actor, version)"
            + " VALUES (?, ?, 'main', 1, 0.000, now(), 'user:test', 0)",
        id,
        recipeId);
    jdbcTemplate.update(
        "UPDATE recipe_recipes SET current_branch_id = ? WHERE id = ?", id, recipeId);
  }

  private void seedVersion(UUID id, UUID recipeId, UUID branchId) {
    jdbcTemplate.update(
        "INSERT INTO recipe_versions (id, recipe_id, branch_id, version_number, change_diff,"
            + " trigger, nutrition_per_serving, embedding_status, created_at, created_by_actor)"
            + " VALUES (?, ?, ?, 1, '{}'::jsonb, 'MANUAL_CREATE', NULL, 'pending', now(),"
            + " 'user:test')",
        id,
        recipeId,
        branchId);
  }

  private void seedIngredient(UUID versionId, int lineOrder, String mappingKey) {
    jdbcTemplate.update(
        "INSERT INTO recipe_ingredients (id, version_id, line_order, ingredient_mapping_key,"
            + " display_name, optional, needs_review)"
            + " VALUES (?, ?, ?, ?, ?, false, false)",
        UUID.randomUUID(),
        versionId,
        lineOrder,
        mappingKey,
        "Display " + mappingKey);
  }
}
