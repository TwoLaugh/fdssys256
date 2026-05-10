package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import com.example.mealprep.nutrition.api.dto.UpdateTargetsRequest;
import com.example.mealprep.nutrition.api.dto.UpsertFoodMoodEntryRequest;
import com.example.mealprep.nutrition.domain.entity.ActivityLevel;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Write API for the nutrition module's targets + intake + daily-activity aggregates. {@code
 * initialiseTargets} (auto-seed at user creation with DRI defaults) ships in 01c — its DRI seed
 * migration is deferred. The intake / activity surface lands in 01b.
 */
public interface NutritionUpdateService {

  /**
   * Replace the user's nutrition targets wholesale. The request's {@code expectedVersion} is
   * matched against the row's current {@code @Version}; mismatch → {@link
   * org.springframework.dao.OptimisticLockingFailureException}.
   *
   * <p>One audit-log row is written per genuinely changed field (no-op fields → no row); writes are
   * atomic with the targets save (same {@code @Transactional}). On commit, a {@link
   * com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent} is published carrying the
   * set of changed field paths.
   *
   * @param userId the targets owner (resolved server-side)
   * @param request the full replacement payload
   * @param actorUserId the user performing the change — equal to {@code userId} for self-edits
   *     today; later sub-tickets layer admin / system actor flows
   */
  TargetsDto updateTargets(UUID userId, UpdateTargetsRequest request, UUID actorUserId);

  /**
   * Pre-fill an intake day from a plan snapshot. In-process only in 01b — no HTTP endpoint accepts
   * this; the planner module (deferred) will inject {@link NutritionUpdateService} and call this
   * method on plan creation. Creates the day row + slot rows; writes a {@code PREFILL} audit row.
   */
  IntakeDayDto prefillFromPlan(
      UUID userId, LocalDate onDate, UUID planId, List<PlannedSlotInputDto> slots);

  /**
   * Mark a planned slot as eaten as planned: copies {@code planned_*} into {@code actual_*}, sets
   * {@code actualStatus = CONFIRMED}. Idempotent on already-CONFIRMED slots (no audit row, no
   * event).
   */
  IntakeDayDto confirmFromPlan(UUID userId, LocalDate onDate, MealSlot mealSlot);

  /**
   * Override a slot with verbatim free text — actuals zeroed, {@code needsAiParse = true}. Real AI
   * parsing deferred to nutrition-01k; the row is preserved for the future listener.
   */
  IntakeDayDto overrideIntakeFromFreeText(
      UUID userId, LocalDate onDate, MealSlot mealSlot, String freeText);

  /** Manually set a slot's actual nutrition values; flips status to {@code EDITED}. */
  IntakeDayDto editIntakeManually(
      UUID userId, LocalDate onDate, MealSlot mealSlot, IntakeEntryDto entry);

  /** Mark a slot as skipped (actuals zeroed, status SKIPPED). */
  IntakeDayDto skipMeal(UUID userId, LocalDate onDate, MealSlot mealSlot);

  /**
   * Log a snack on a date. Auto-creates the day row if missing. {@code deductFromPantry} flag is
   * accepted but a no-op in 01b (deferred to nutrition-01l).
   */
  IntakeDayDto logSnack(UUID userId, LocalDate onDate, LogSnackRequest request);

  /** Remove a snack by id. Throws {@code IntakeSnackNotFoundException} on cross-user access. */
  IntakeDayDto removeSnack(UUID userId, LocalDate onDate, UUID snackId);

  /**
   * Upsert the daily activity entry for a date. Last write wins (no version). No audit log written.
   */
  DailyActivityDto upsertDailyActivity(
      UUID userId, LocalDate onDate, ActivityLevel level, String notes);

  /**
   * Create a new food/mood journal entry. {@code expectedVersion} on the request is ignored on
   * insert. Slot-tied collision on {@code (userId, onDate, mealSlot)} (with non-null slot) is left
   * to the DB unique constraint and surfaces as 409.
   */
  FoodMoodEntryDto upsertJournalEntry(UUID userId, UpsertFoodMoodEntryRequest request);

  /**
   * Update an existing food/mood journal entry. {@code request.onDate} must equal the entity's
   * {@code onDate} (cross-day moves require DELETE + POST). Cross-user / wrong-date access surfaces
   * as 404 to avoid leaking existence; stale {@code expectedVersion} surfaces as 409.
   */
  FoodMoodEntryDto updateJournalEntry(
      UUID userId, UUID entryId, UpsertFoodMoodEntryRequest request);

  /**
   * Hard-delete a food/mood journal entry. 404 on missing / not-owned. The {@code AFTER_COMMIT}
   * event carries {@code action = DELETED}.
   */
  void deleteJournalEntry(UUID userId, UUID entryId);
}
