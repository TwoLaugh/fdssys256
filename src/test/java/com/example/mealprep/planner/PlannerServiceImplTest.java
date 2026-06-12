package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.PlanReoptSuggestionDto;
import com.example.mealprep.planner.api.mapper.PlanMapper;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper;
import com.example.mealprep.planner.domain.entity.MealPrepPlanReoptSuggestion;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.ReoptSuggestionStatus;
import com.example.mealprep.planner.domain.entity.ReoptTriggerKind;
import com.example.mealprep.planner.domain.repository.MealPrepPlanReoptSuggestionRepository;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.service.internal.PlannerServiceImpl;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@link PlannerServiceImpl}. The lazy-touch + mapper pattern is exercised
 * end-to-end against Postgres in {@code PlannerFlowIT}; this class only verifies the {@code
 * Optional.empty()} branch and the mapper-is-invoked branch.
 */
@ExtendWith(MockitoExtension.class)
class PlannerServiceImplTest {

  @Mock private PlanRepository planRepository;
  @Mock private MealPrepPlanReoptSuggestionRepository planReoptSuggestionRepository;
  @Mock private PlanMapper planMapper;
  @Mock private ReoptSuggestionMapper reoptSuggestionMapper;

  @InjectMocks private PlannerServiceImpl service;

  @Test
  void getPlanById_whenPlanMissing_returnsEmpty_andSkipsMapper() {
    UUID planId = UUID.randomUUID();
    when(planRepository.findById(planId)).thenReturn(Optional.empty());

    Optional<PlanDto> result = service.getPlanById(planId);

    assertThat(result).isEmpty();
    verifyNoInteractions(planMapper);
  }

  @Test
  void getPlanById_whenPlanPresent_invokesMapper_andReturnsResult() {
    Plan plan = PlanTestData.newPlanGraph(LocalDate.of(2026, 5, 11), 1, 1);
    PlanDto expectedDto = Mockito.mock(PlanDto.class);
    when(planRepository.findById(plan.getId())).thenReturn(Optional.of(plan));
    when(planMapper.toDto(any(Plan.class))).thenReturn(expectedDto);

    Optional<PlanDto> result = service.getPlanById(plan.getId());

    assertThat(result).hasValue(expectedDto);
    verify(planMapper).toDto(plan);
  }

  // ============================================================================================
  // getPlanReoptSuggestion (frontend-gaps/planner-reopt-suggestion-detail)
  // ============================================================================================

  private static MealPrepPlanReoptSuggestion suggestionFor(UUID planId) {
    return MealPrepPlanReoptSuggestion.builder()
        .id(UUID.randomUUID())
        .planId(planId)
        .triggerKind(ReoptTriggerKind.USER)
        .triggerEventId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .summary("1 change")
        .status(ReoptSuggestionStatus.PENDING)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .swept(false)
        .build();
  }

  @Test
  void getPlanReoptSuggestion_whenSuggestionMissing_returnsEmpty_andSkipsMapper() {
    UUID suggestionId = UUID.randomUUID();
    when(planReoptSuggestionRepository.findById(suggestionId)).thenReturn(Optional.empty());

    Optional<PlanReoptSuggestionDto> result =
        service.getPlanReoptSuggestion(UUID.randomUUID(), suggestionId);

    assertThat(result).isEmpty();
    verifyNoInteractions(reoptSuggestionMapper);
  }

  @Test
  void getPlanReoptSuggestion_whenSuggestionBelongsToAnotherPlan_returnsEmpty_andSkipsMapper() {
    MealPrepPlanReoptSuggestion suggestion = suggestionFor(UUID.randomUUID());
    when(planReoptSuggestionRepository.findById(suggestion.getId()))
        .thenReturn(Optional.of(suggestion));

    // Path-scoping: a hit on the id but a different {planId} must behave like a miss (404 at the
    // controller), exactly as accept/reject do.
    Optional<PlanReoptSuggestionDto> result =
        service.getPlanReoptSuggestion(UUID.randomUUID(), suggestion.getId());

    assertThat(result).isEmpty();
    verifyNoInteractions(reoptSuggestionMapper);
  }

  @Test
  void getPlanReoptSuggestion_whenPlanMatches_mapsStoredAggregateToDto() {
    UUID planId = UUID.randomUUID();
    MealPrepPlanReoptSuggestion suggestion = suggestionFor(planId);
    PlanReoptSuggestionDto expectedDto = Mockito.mock(PlanReoptSuggestionDto.class);
    when(planReoptSuggestionRepository.findById(suggestion.getId()))
        .thenReturn(Optional.of(suggestion));
    when(reoptSuggestionMapper.toPlanReoptDto(suggestion)).thenReturn(expectedDto);

    Optional<PlanReoptSuggestionDto> result =
        service.getPlanReoptSuggestion(planId, suggestion.getId());

    assertThat(result).hasValue(expectedDto);
    verify(reoptSuggestionMapper).toPlanReoptDto(suggestion);
  }
}
