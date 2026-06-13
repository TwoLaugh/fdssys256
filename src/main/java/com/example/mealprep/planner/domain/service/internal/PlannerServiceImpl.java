package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.planner.api.dto.FeasibilityCheckResultDto;
import com.example.mealprep.planner.api.dto.GeneratePlanRequest;
import com.example.mealprep.planner.api.dto.PlanCompositionContext;
import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import com.example.mealprep.planner.api.mapper.PlanMapper;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.repository.ReoptSuggestionRepository;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import com.example.mealprep.planner.domain.service.internal.composer.ConstraintFeasibilityCheck;
import com.example.mealprep.planner.domain.service.internal.composer.PlanCompositionContextBuilder;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single impl of {@link PlanQueryService}. Lives in {@code domain.service.internal} per the style
 * guide — {@code public} is needed for Spring proxy generation and for cross-package injection of
 * the interface. {@link PlanQueryService} is the cross-module surface; this impl is the only
 * injectable bean.
 *
 * <p>Future tickets (01j) will add {@code PlannerService} (write surface) to this same class so the
 * planner module has one impl backing both interfaces, per the style guide.
 *
 * <p>The read path uses the lazy-touch-inside-{@code @Transactional} pattern to avoid Hibernate's
 * {@code MultipleBagFetchException} on three chained {@code @OneToMany List<>} collections (see
 * {@code PlanRepository} javadoc). The mapper runs inside the same transaction so all child
 * collections materialise while the session is open — no {@code LazyInitializationException} on the
 * controller's response-serialise path.
 */
@Service
public class PlannerServiceImpl implements PlanQueryService {

  /** Cap on the history endpoint — see {@link #getPlanHistory(UUID, LocalDate)}. */
  static final int HISTORY_CAP = 100;

  private final PlanRepository planRepository;
  private final ReoptSuggestionRepository reoptSuggestionRepository;
  private final MealPrepPlanReoptSuggestionRepository planReoptSuggestionRepository;
  private final PlanMapper planMapper;
  private final ReoptSuggestionMapper reoptSuggestionMapper;
  private final HouseholdQueryService householdQueryService;
  private final LifestyleConfigQueryService lifestyleConfigQueryService;
  private final PlanCompositionContextBuilder contextBuilder;
  private final ConstraintFeasibilityCheck feasibilityCheck;

  public PlannerServiceImpl(
      PlanRepository planRepository,
      ReoptSuggestionRepository reoptSuggestionRepository,
      MealPrepPlanReoptSuggestionRepository planReoptSuggestionRepository,
      PlanMapper planMapper,
      ReoptSuggestionMapper reoptSuggestionMapper,
      HouseholdQueryService householdQueryService,
      LifestyleConfigQueryService lifestyleConfigQueryService,
      PlanCompositionContextBuilder contextBuilder,
      ConstraintFeasibilityCheck feasibilityCheck) {
    this.planRepository = planRepository;
    this.reoptSuggestionRepository = reoptSuggestionRepository;
    this.planReoptSuggestionRepository = planReoptSuggestionRepository;
    this.planMapper = planMapper;
    this.reoptSuggestionMapper = reoptSuggestionMapper;
    this.householdQueryService = householdQueryService;
    this.lifestyleConfigQueryService = lifestyleConfigQueryService;
    this.contextBuilder = contextBuilder;
    this.feasibilityCheck = feasibilityCheck;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlanDto> getPlanById(UUID planId) {
    return planRepository.findById(planId).map(this::hydrateAndMap);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlanDto> getActivePlan(UUID householdId, LocalDate weekStartDate) {
    return planRepository
        .findFirstByHouseholdIdAndWeekStartDateAndStatus(
            householdId, weekStartDate, PlanStatus.ACTIVE)
        .map(this::hydrateAndMap);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanDto> getPlanHistory(UUID householdId, LocalDate weekStartDate) {
    Page<Plan> page =
        planRepository.findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
            householdId, weekStartDate, PageRequest.of(0, HISTORY_CAP));
    if (page.isEmpty()) {
      return Collections.emptyList();
    }
    List<PlanDto> out = new ArrayList<>(page.getNumberOfElements());
    for (Plan plan : page.getContent()) {
      out.add(hydrateAndMap(plan));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PlanDto> getPlansBetween(
      UUID householdId, LocalDate from, LocalDate to, Pageable pageable) {
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from must be <= to");
    }
    Page<Plan> page =
        planRepository
            .findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
                householdId, from, to, pageable);
    return page.map(this::hydrateAndMap);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PlanDto> getPlansByIds(List<UUID> planIds) {
    if (planIds == null || planIds.isEmpty()) {
      return Collections.emptyList();
    }
    List<Plan> plans = planRepository.findByIdIn(planIds);
    if (plans.isEmpty()) {
      return Collections.emptyList();
    }
    List<PlanDto> out = new ArrayList<>(plans.size());
    for (Plan plan : plans) {
      out.add(hydrateAndMap(plan));
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ReoptSuggestionDto> getPendingSuggestions(UUID householdId, Pageable pageable) {
    Page<ReoptSuggestionDto> mapped =
        reoptSuggestionRepository
            .findByHouseholdIdAndStatusOrderByCreatedAtDesc(
                householdId, ReoptStatus.PENDING, pageable)
            .map(reoptSuggestionMapper::toDto);
    // Materialise as a PageImpl so the caller's serialisation walks a concrete list, not a
    // lazy proxy that could try to use the session after close.
    return new PageImpl<>(mapped.getContent(), pageable, mapped.getTotalElements());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ReoptSuggestionDto> getSuggestion(UUID suggestionId) {
    return reoptSuggestionRepository.findById(suggestionId).map(reoptSuggestionMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<PlanReoptSuggestionDto> getPlanReoptSuggestion(UUID planId, UUID suggestionId) {
    return planReoptSuggestionRepository
        .findById(suggestionId)
        .filter(suggestion -> suggestion.getPlanId().equals(planId))
        .map(reoptSuggestionMapper::toPlanReoptDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UpcomingSlotView> getUpcomingSlots(
      UUID householdId, LocalDate fromDate, LocalDate toDate) {
    if (fromDate.isAfter(toDate)) {
      throw new IllegalArgumentException("fromDate must be <= toDate");
    }
    List<Plan> activePlans =
        planRepository.findByHouseholdIdAndStatusIn(householdId, List.of(PlanStatus.ACTIVE));
    if (activePlans.isEmpty()) {
      return Collections.emptyList();
    }
    // Batch-load the household owner's lifestyle-config meal-timing map ONCE per call (not per
    // slot), so the cross-module read cost is independent of the slot count (planner-01m §32).
    Map<String, String> mealTimingMap = ownerMealTimingMap(householdId);
    List<UpcomingSlotView> out = new ArrayList<>();
    for (Plan plan : activePlans) {
      for (var day : plan.getDays()) {
        LocalDate date = day.getOnDate();
        if (date.isBefore(fromDate) || date.isAfter(toDate)) {
          continue;
        }
        for (var slot : day.getSlots()) {
          var scheduled = slot.getScheduledRecipe();
          out.add(
              new UpcomingSlotView(
                  slot.getId(),
                  plan.getId(),
                  plan.getHouseholdId(),
                  date,
                  slot.getKind(),
                  slot.getSlotIndex(),
                  slot.getTimeBudgetMin(),
                  scheduled == null ? null : scheduled.getRecipeId(),
                  MealTimeResolver.resolveTime(slot, mealTimingMap),
                  slot.getPrepStepAtTime()));
        }
      }
    }
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public FeasibilityCheckResultDto checkFeasibility(UUID householdId, LocalDate weekStartDate) {
    // Resolve a representative caller (the household owner) for the user-scoped cross-module reads
    // the context builder performs (provisions bundle, household settings, member roster). The
    // feasibility check itself is household-level and read-only; no plan is created.
    UUID ownerUserId = ownerUserIdFor(householdId);
    GeneratePlanRequest request = new GeneratePlanRequest(householdId, weekStartDate, false);
    PlanCompositionContext context =
        contextBuilder.build(request, ownerUserId, UUID.randomUUID(), null);
    return feasibilityCheck.check(context);
  }

  /**
   * Resolve the household owner's lifestyle-config {@code meal_timing.preferred_schedule} map, or
   * an empty map when there is no household, no owner, no lifestyle config, or no meal-timing
   * section. Looked up once per {@code getUpcomingSlots} call. The "owner" is the household's
   * {@code primary} member, falling back to its creator.
   */
  private Map<String, String> ownerMealTimingMap(UUID householdId) {
    UUID ownerUserId = ownerUserIdFor(householdId);
    if (ownerUserId == null) {
      return Map.of();
    }
    return lifestyleConfigQueryService
        .getLifestyleConfig(ownerUserId)
        .map(LifestyleConfigDto::document)
        .map(LifestyleConfigDocument::mealTiming)
        .map(LifestyleConfigDocument.MealTiming::preferredSchedule)
        .map(LifestyleConfigDocument.MealSchedule::times)
        .orElseGet(Map::of);
  }

  /**
   * The household owner's user id — the {@code primary} member if present, else the household's
   * creator. Returns null when the household is unknown or has no members.
   */
  private UUID ownerUserIdFor(UUID householdId) {
    Optional<HouseholdDto> household = householdQueryService.getById(householdId);
    if (household.isEmpty()) {
      return null;
    }
    HouseholdDto h = household.get();
    List<HouseholdMemberDto> members = h.members() == null ? List.of() : h.members();
    return members.stream()
        .filter(m -> m.role() == HouseholdRole.primary)
        .map(HouseholdMemberDto::userId)
        .findFirst()
        .orElse(h.createdByUserId());
  }

  /**
   * Touch lazy children while the session is open, then run the mapper. The chained iteration is
   * the explicit alternative to {@code @EntityGraph} (which would trigger {@code
   * MultipleBagFetchException} on the three {@code @OneToMany List<>} collections).
   *
   * <p>The household owner's lifestyle-config {@code meal_timing} map is loaded <b>once per
   * plan</b> here and threaded down the mapper chain so the per-slot {@code effectiveMealTime}
   * resolution (frontend-gaps: planner-effective-meal-time) costs one preference read per plan
   * mapping, never one per slot.
   */
  private PlanDto hydrateAndMap(Plan plan) {
    plan.getDays()
        .forEach(
            day ->
                day.getSlots()
                    .forEach(
                        slot -> {
                          // Touch the optional @OneToOne to force lazy load.
                          slot.getScheduledRecipe();
                        }));
    return planMapper.toDto(plan, ownerMealTimingMap(plan.getHouseholdId()));
  }
}
