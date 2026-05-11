package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.DirectiveStatus;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.HealthDirectiveDto;
import com.example.mealprep.nutrition.api.dto.IngredientLookupRequest;
import com.example.mealprep.nutrition.api.dto.IngredientLookupResultDto;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.api.dto.IntakeAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the nutrition module's targets aggregate. Cross-module callers (planner, floor-gate,
 * intake aggregation in later sub-tickets) consume this to retrieve a user's targets — partial in
 * 01a (targets only), extended in 01b with the intake + daily-activity reads.
 */
public interface NutritionQueryService {

  /**
   * Read-by-others contract: return the calling user's nutrition targets, or empty if no row exists
   * yet. The returned DTO includes all four child collections (per-meal distribution,
   * micro-targets, eating window, activity adjustments) eagerly populated.
   */
  Optional<TargetsDto> getTargets(UUID userId);

  /** Paginated audit log of changes to the user's targets, newest-first. */
  Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable);

  /**
   * Read the user's intake for a single day. Returns empty if no day row exists; the day does NOT
   * auto-create on read.
   */
  Optional<IntakeDayDto> getIntakeForDay(UUID userId, LocalDate onDate);

  /**
   * Range read of intake days in {@code [from, to]} inclusive, max 35 days. Days that don't exist
   * are not represented in the result. Throws {@code InvalidIntakeRangeException} if the range is
   * invalid.
   */
  List<IntakeDayDto> getIntakeRange(UUID userId, LocalDate from, LocalDate to);

  /**
   * Paginated audit log for an intake day, newest-first. Returns an empty page if the day row does
   * not exist (consistent with targets-audit semantics).
   */
  Page<IntakeAuditEntryDto> getIntakeAuditLog(UUID userId, LocalDate onDate, Pageable pageable);

  /** Read the user's daily activity entry for a single date, or empty if not logged. */
  Optional<DailyActivityDto> getDailyActivity(UUID userId, LocalDate onDate);

  /** Range read of daily activity entries in {@code [from, to]} inclusive, max 35 days. */
  List<DailyActivityDto> getDailyActivityRange(UUID userId, LocalDate from, LocalDate to);

  /**
   * Read the user's food/mood journal entries for a single date, sorted by {@code loggedAt} ASC.
   * Returns an empty list when no entries exist (not 404).
   */
  List<FoodMoodEntryDto> getJournalEntriesForDay(UUID userId, LocalDate onDate);

  /**
   * Paginated read of the user's recent journal entries, newest-first ({@code loggedAt DESC}).
   * Default size 20, max 100; clamping is enforced at the controller boundary.
   */
  Page<FoodMoodEntryDto> getRecentJournalEntries(UUID userId, Pageable pageable);

  /**
   * Cross-module read helper for the (future) Feedback System: returns the top 20 journal entries
   * for {@code userId} sorted newest-first. No HTTP exposure — invoked in-process when the feedback
   * module lands.
   */
  List<FoodMoodEntryDto> getJournalEntriesForFeedbackContext(UUID userId);

  /**
   * Cross-module read helper: cache-only lookup of an ingredient mapping by (normalised) search
   * term. Does NOT invoke the USDA / OFF pipeline — pure cache read. Callers that want a pipeline-
   * resolving lookup go through the HTTP {@code GET /api/v1/nutrition/ingredients/lookup?term=}.
   */
  Optional<IngredientNutritionDto> lookupIngredient(String searchTerm);

  /**
   * Batch sibling of {@link #lookupIngredient(String)}: normalises each, returns only the cache
   * hits. Issues a single SQL {@code WHERE search_term IN (...)} (no N+1).
   */
  List<IngredientNutritionDto> lookupIngredients(Collection<String> searchTerms);

  /**
   * UI search over the ingredient-mapping cache. {@code cacheOnly = true} in v1 — the live USDA /
   * OFF discovery path is deferred to nutrition-01m.
   */
  IngredientLookupResultDto searchIngredientsForUi(IngredientLookupRequest request);

  /**
   * Paginated list of mappings whose {@code needs_review} flag is true, sorted {@code updated_at
   * DESC}. Backs {@code GET /api/v1/nutrition/ingredients/needs-review}.
   */
  Page<IngredientNutritionDto> getMappingsNeedingReview(Pageable pageable);

  /**
   * Paginated list of the caller's health directives, optionally filtered by {@code
   * DirectiveStatus}, sorted {@code received_at DESC}. Default size 20, max 100 (clamped at the
   * controller boundary).
   */
  Page<HealthDirectiveDto> getDirectives(UUID userId, DirectiveStatus filter, Pageable pageable);

  /**
   * Fetch a single directive by id. Returns empty when the row doesn't exist OR when it belongs to
   * a different user — collapsed to 404 at the controller boundary so we don't leak existence.
   */
  Optional<HealthDirectiveDto> getDirective(UUID actorUserId, UUID directiveId);
}
