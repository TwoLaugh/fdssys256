package com.example.mealprep.planner.domain.service.internal;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.mapper.PlanMapper;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.repository.ReoptSuggestionRepository;
import com.example.mealprep.planner.domain.service.PlanQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
  private final PlanMapper planMapper;
  private final ReoptSuggestionMapper reoptSuggestionMapper;

  public PlannerServiceImpl(
      PlanRepository planRepository,
      ReoptSuggestionRepository reoptSuggestionRepository,
      PlanMapper planMapper,
      ReoptSuggestionMapper reoptSuggestionMapper) {
    this.planRepository = planRepository;
    this.reoptSuggestionRepository = reoptSuggestionRepository;
    this.planMapper = planMapper;
    this.reoptSuggestionMapper = reoptSuggestionMapper;
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

  /**
   * Touch lazy children while the session is open, then run the mapper. The chained iteration is
   * the explicit alternative to {@code @EntityGraph} (which would trigger {@code
   * MultipleBagFetchException} on the three {@code @OneToMany List<>} collections).
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
    return planMapper.toDto(plan);
  }
}
