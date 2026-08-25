package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.AcceptDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.DailyActivityDto;
import com.example.mealprep.nutrition.api.dto.FeedbackTargetAdjustment;
import com.example.mealprep.nutrition.api.dto.FoodMoodEntryDto;
import com.example.mealprep.nutrition.api.dto.HealthDirectiveDto;
import com.example.mealprep.nutrition.api.dto.InboundHealthDirectiveRequest;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDocument;
import com.example.mealprep.nutrition.api.dto.IngredientNutritionDto;
import com.example.mealprep.nutrition.api.dto.IntakeDayDto;
import com.example.mealprep.nutrition.api.dto.IntakeEntryDto;
import com.example.mealprep.nutrition.api.dto.LogSnackRequest;
import com.example.mealprep.nutrition.api.dto.PlannedSlotInputDto;
import com.example.mealprep.nutrition.api.dto.RejectDirectiveRequest;
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
 * initialiseTargets} bootstraps a user's targets at onboarding, seeding DRI micro defaults from the
 * {@code nutrition_dri_defaults} seed table for any micronutrient the onboarding wizard did not
 * supply.
 */
public interface NutritionUpdateService {

  /**
   * Bootstrap a user's nutrition targets at onboarding (nutrition-7 / LLD §NutritionUpdateService
   * line 705). Creates the aggregate from {@code request} (the onboarding wizard supplies the
   * macros + any age/sex-tuned micro overrides it has computed) and then DRI-seeds any
   * micronutrient the request did NOT specify from the {@code nutrition_dri_defaults} seed table
   * (per the HLD Bootstrapping section: "Micro targets defaulted from standard dietary reference
   * intakes"). DRI defaults are seeded as warning-only micros ({@code is_hard_floor = false}).
   *
   * <p>Idempotent on the {@code (user_id)} unique constraint: calling it when a targets row already
   * exists throws {@link org.springframework.dao.DataIntegrityViolationException} (the caller is
   * expected to {@code updateTargets} thereafter). The create is audited and publishes a {@link
   * com.example.mealprep.nutrition.event.NutritionTargetsChangedEvent}, consistent with the
   * create-via-PUT leg.
   *
   * @param userId the targets owner
   * @param request the onboarding-computed payload (macros + any explicit micro overrides)
   */
  TargetsDto initialiseTargets(UUID userId, UpdateTargetsRequest request);

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
   * Apply a single-field, relative feedback-driven adjustment to one nutrition target. Unlike
   * {@link #updateTargets}, this is NOT a full-document replacement: it reads the current value of
   * the named target, nudges it by a relative magnitude in the given direction, and writes only
   * that field — safe to call fire-and-forget from the feedback bridge (no client-supplied
   * expectedVersion; the {@code @Version} bump is internal). Writes one {@code
   * nutrition_targets_audit} row with {@code actor_kind = feedback} ({@code actor_type = AI},
   * {@code origin_trace = feedback-<feedback_id>}). Publishes {@code NutritionTargetsChangedEvent}
   * carrying the single changed field path.
   *
   * <p>If {@code adjustment.absoluteValue()} is present it sets the target to it directly (ignoring
   * the relative magnitude); otherwise the value is {@code current ± pct·current} per the
   * configured magnitude steps, clamped to a sanity floor (never driven to zero or below).
   * Adjusting a {@code micro.<key>} target whose row does not exist is a no-op (no row created, no
   * audit, no event) — returns the unchanged targets. An unknown {@code target} throws {@link
   * com.example.mealprep.nutrition.exception.InvalidFeedbackAdjustmentException} (422). Enforcement
   * direction / hard-floor semantics are never touched.
   *
   * <p>Plain {@code @Transactional} (REQUIRED): the bridge calls this from inside its {@code
   * REQUIRES_NEW} {@code TransactionTemplate}, so the target update + audit row + the bridge's
   * idempotency row commit as one unit. Do NOT make this {@code REQUIRES_NEW} (decision-log 0010).
   *
   * @param userId the targets owner
   * @param adjustment the classifier-shaped single-field adjustment
   */
  TargetsDto applyFeedbackAdjustment(UUID userId, FeedbackTargetAdjustment adjustment);

  /**
   * Pre-fill an intake day from a plan snapshot. In-process only — no HTTP endpoint accepts this;
   * the production caller is the nutrition module's {@code PlanAcceptedPrefillListener} on plan
   * acceptance. Creates the day row + slot rows; writes a {@code PREFILL} audit row.
   *
   * <p>Idempotent re-prefill: decided slots (anything not PENDING) are preserved verbatim so
   * user-entered actuals are never clobbered; PENDING slots are updated in place to the new
   * snapshot, stale PENDING slots removed, missing meal slots added.
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

  /**
   * Manually set a slot's actual nutrition values; flips status to {@code EDITED} and clears {@code
   * needsAiParse}. Legal from {@code PENDING}, and — as the repair path for a parse-failed override
   * — from {@code OVERRIDDEN} with {@code needsAiParse = true} ({@code overrideFreeText} is
   * retained for provenance). Any other decided state throws {@code IntakeSlotNotEditableException}
   * (422) — no backwards transitions.
   */
  IntakeDayDto editIntakeManually(
      UUID userId, LocalDate onDate, MealSlot mealSlot, IntakeEntryDto entry);

  /** Mark a slot as skipped (actuals zeroed, status SKIPPED). */
  IntakeDayDto skipMeal(UUID userId, LocalDate onDate, MealSlot mealSlot);

  /**
   * Log a snack on a date. Auto-creates the day row if missing. {@code deductFromPantry = true}
   * hands off to {@code ProvisionUpdateService.applyStandaloneConsumption} in the same transaction
   * (nutrition-01l); it requires {@code ingredientMappingKey}, else {@code
   * SnackDeductWithoutMappingKeyException} (400). A key matching no pantry row deducts nothing, by
   * design.
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

  /**
   * Upgrade an ingredient mapping with a user-confirmed override. Bumps {@code source = MANUAL},
   * {@code confidence = 1.0}, {@code needsReview = false}, sets {@code lastVerifiedAt}, and bumps
   * the {@code @Version}.
   *
   * <p>LLD line 729 names the verb; 01d widens the signature to take {@code expectedVersion} too
   * (the controller carries it from the wire DTO; mismatch surfaces as 409 via {@code
   * OptimisticLockingFailureException}). Publishes {@code IngredientMappingCorrectedEvent}
   * AFTER_COMMIT.
   *
   * @throws com.example.mealprep.nutrition.exception.IngredientMappingNotFoundException if no row
   *     matches the (normalised) search term
   */
  IngredientNutritionDto correctIngredientMapping(
      String searchTerm,
      IngredientNutritionDocument override,
      long expectedVersion,
      UUID actorUserId);

  /**
   * Inbound endpoint for a health platform pushing a new directive. Idempotent on {@code
   * (sourcePlatform, externalDirectiveId)} — a re-delivery raises {@code
   * DuplicateHealthDirectiveException} (409). Persists the row as {@code PENDING_REVIEW} and
   * publishes {@code HealthDirectiveReceivedEvent} AFTER_COMMIT.
   */
  HealthDirectiveDto receiveInboundDirective(
      UUID actorUserId, InboundHealthDirectiveRequest request);

  /**
   * Accept a pending directive, run the deterministic safety gate, and route the deltas via {@code
   * DirectiveApplier}. Persists the gate's verdict + findings on the directive regardless of
   * outcome. Publishes {@code HealthDirectiveAcceptedEvent} AFTER_COMMIT.
   */
  HealthDirectiveDto acceptHealthDirective(
      UUID actorUserId, UUID directiveId, AcceptDirectiveRequest request);

  /**
   * Reject a pending directive — records the reason; no safety gate, no event (LLD §Events doesn't
   * declare a rejection event).
   */
  HealthDirectiveDto rejectHealthDirective(
      UUID actorUserId, UUID directiveId, RejectDirectiveRequest request);

  /**
   * Auto-expiry sweep (LLD Flow 8 line 1022): for every {@code ACCEPTED} directive whose {@code
   * auto_expires_at} has passed, instruct the source module to revert any temporary effects (e.g. a
   * 6-week egg-elimination hard constraint via {@code
   * PreferenceUpdateService.removeTemporaryConstraint}) and transition the directive to {@code
   * EXPIRED}. Each directive is processed in its own transaction so one bad row does not block the
   * rest. Idempotent — a directive already {@code EXPIRED} is not re-swept. Driven by a daily
   * {@code @Scheduled} job; also callable directly (tests / ops).
   *
   * @return the number of directives transitioned to {@code EXPIRED}
   */
  int sweepExpiredDirectives();
}
