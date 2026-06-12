package com.example.mealprep.planner.domain.service;

import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public read surface of the planner module.
 *
 * <p>01a shipped {@link #getPlanById(UUID)}; 01c appends the household-scoped reads
 * (active/history/range/by-ids) plus the suggestion reads. {@link #checkFeasibility(UUID,
 * LocalDate)} (LLD line ~577) is implemented by planner-6: it builds the cross-module composition
 * context and runs the Pre-A {@code ConstraintFeasibilityCheck}.
 *
 * <p>Every read returns a hydrated DTO graph — child collections (days → slots → scheduledRecipe)
 * are materialised inside the service's {@code @Transactional(readOnly = true)} boundary so the
 * controller's serialisation path never trips a {@code LazyInitializationException}. See {@link
 * com.example.mealprep.planner.domain.service.internal.PlannerServiceImpl} for the implementation
 * pattern.
 */
public interface PlanQueryService {

  /**
   * Fetch a single plan by id, fully hydrated (days, slots, scheduled recipes). Days are ordered by
   * date; slots within each day are ordered by slot index. Returns {@link Optional#empty()} when
   * the plan does not exist.
   */
  Optional<PlanDto> getPlanById(UUID planId);

  /**
   * Fetch the single {@link com.example.mealprep.planner.domain.entity.PlanStatus#ACTIVE} plan for
   * a (household, week). Returns {@link Optional#empty()} when no ACTIVE plan exists (e.g. the
   * household has only DRAFT / GENERATED / SUPERSEDED rows for that week).
   */
  Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate);

  /**
   * All plan generations for a specific (household, week), latest generation first. Capped at 100
   * rows (latest 100 generations) to bound the response size if a user mashes regenerate;
   * pagination over history is unwarranted because the typical row count is &lt;10.
   *
   * <p>Returns an empty list (never {@code null}) when no plans exist for that week.
   */
  List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate);

  /**
   * Paginated range query — plans whose {@code weekStartDate} falls in {@code [from, to]}
   * inclusive, sorted by {@code (weekStartDate DESC, generation DESC)}. The sort is locked at the
   * repository level; the caller-supplied {@code Pageable}'s {@code Sort} is ignored.
   *
   * @throws IllegalArgumentException when {@code from > to}
   */
  Page<PlanDto> getPlansBetween(UUID householdId, LocalDate from, LocalDate to, Pageable pageable);

  /**
   * Bulk hydrate plans by id. In-process method; no REST surface in 01c. Used by sibling modules
   * (grocery / notification) that already hold a list of plan IDs from their own event payloads.
   *
   * <p>The result list is in <strong>arbitrary order</strong> — JPA's {@code findByIdIn} does not
   * preserve input order. Empty input returns an empty list with no SQL issued. Unknown ids are
   * silently dropped.
   */
  List<PlanDto> getPlansByIds(List<UUID> planIds);

  /**
   * Paginated list of {@link com.example.mealprep.planner.domain.entity.ReoptStatus#PENDING} re-opt
   * suggestions for a household, sorted by {@code createdAt DESC}. Dismissed / accepted / expired
   * suggestions are excluded.
   */
  Page<ReoptSuggestionDto> getPendingSuggestions(UUID householdId, Pageable pageable);

  /**
   * Fetch a single re-opt suggestion by id. In-process method; the per-suggestion REST surface
   * lands with the dismiss endpoint in planner-01k. Returns {@link Optional#empty()} when the
   * suggestion does not exist.
   */
  Optional<ReoptSuggestionDto> getSuggestion(UUID suggestionId);

  /**
   * Fetch a single materialised mid-week re-opt suggestion (the planner-01i {@code
   * MealPrepPlanReoptSuggestion} aggregate) scoped to its plan — the read behind {@code GET
   * /api/v1/plans/{planId}/reopt-suggestions/{suggestionId}} so the UI can render the
   * before&rarr;after diff preview ({@code proposedAssignments.changes[]}) <em>before</em> the user
   * accepts.
   *
   * <p>Side-effect-free: returns the <strong>stored</strong> proposal as persisted when the
   * suggestion was raised — no recomputation, no status transition, no decision-log write. The
   * proposal may therefore be stale against later slot-state drift (e.g. a slot pinned EATEN after
   * the suggestion was raised); the accept path's validation stays authoritative. Any status is
   * readable (PENDING for the preview; ACCEPTED / REJECTED / EXPIRED for history).
   *
   * <p>Returns {@link Optional#empty()} when the suggestion does not exist <em>or</em> does not
   * belong to {@code planId} (same path-scoping rule as accept/reject — the caller maps both to
   * 404).
   */
  Optional<PlanReoptSuggestionDto> getPlanReoptSuggestion(UUID planId, UUID suggestionId);

  /**
   * Flat list of upcoming meal slots for a household whose day date falls in {@code [fromDate,
   * toDate]} inclusive, drawn from the household's {@code ACTIVE} plans only. Read-only
   * cross-module helper for the notification/01b {@code PrepReminderScanner}; returns one {@link
   * UpcomingSlotView} per slot (empty list when the household has no active plan in the window). No
   * HTTP exposure.
   */
  List<UpcomingSlotView> getUpcomingSlots(UUID householdId, LocalDate fromDate, LocalDate toDate);

  /**
   * Pre-Stage-A constraint feasibility check for a (household, week) — the surface behind {@code
   * GET /api/v1/plans/feasibility} (planner-6, LLD §PlanQueryService /
   * §ConstraintFeasibilityCheck). The UI calls this before triggering generation so a resolution
   * dialog can render the conflicts + ranked resolutions even before Stage A starts. Read-only; the
   * cross-module composition context is assembled server-side (the household owner is resolved as
   * the representative caller for the provisions / settings reads).
   */
  FeasibilityCheckResultDto checkFeasibility(UUID householdId, LocalDate weekStartDate);
}
