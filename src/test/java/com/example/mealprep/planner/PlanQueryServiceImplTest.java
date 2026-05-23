package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.mealprep.planner.api.dto.PlanDto;
import com.example.mealprep.planner.api.dto.ReoptSuggestionDto;
import com.example.mealprep.planner.api.dto.UpcomingSlotView;
import com.example.mealprep.planner.api.mapper.PlanMapper;
import com.example.mealprep.planner.api.mapper.ReoptSuggestionMapper;
import com.example.mealprep.planner.domain.entity.Plan;
import com.example.mealprep.planner.domain.entity.PlanStatus;
import com.example.mealprep.planner.domain.entity.ReoptStatus;
import com.example.mealprep.planner.domain.entity.ReoptSuggestion;
import com.example.mealprep.planner.domain.repository.PlanRepository;
import com.example.mealprep.planner.domain.repository.ReoptSuggestionRepository;
import com.example.mealprep.planner.domain.service.internal.PlannerServiceImpl;
import com.example.mealprep.planner.testdata.PlanTestData;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Pure unit test for the 01c additions on {@link PlannerServiceImpl}. The lazy-touch + mapper
 * pattern is exercised end-to-end against Postgres in {@code PlansControllerReadIT}; this class
 * covers branch behaviour, ordering, validation, and mapper-invocation contracts against mocked
 * repositories.
 *
 * <p>Note for future agents: {@link PlannerServiceImpl} implements {@link
 * com.example.mealprep.planner.domain.service.PlanQueryService} only in 01c. When planner-01j adds
 * {@code PlannerService} (the write surface) to the same impl, ITs that {@code @MockBean} either
 * interface must mock both — they share a single bean.
 */
@ExtendWith(MockitoExtension.class)
class PlanQueryServiceImplTest {

  @Mock private PlanRepository planRepository;
  @Mock private ReoptSuggestionRepository reoptSuggestionRepository;
  @Mock private PlanMapper planMapper;
  @Mock private ReoptSuggestionMapper reoptSuggestionMapper;

  @InjectMocks private PlannerServiceImpl service;

  // ---------------- getActivePlan ----------------

  @Test
  void getActivePlan_whenNoActivePlan_returnsEmpty_andSkipsMapper() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            householdId, week, PlanStatus.ACTIVE))
        .thenReturn(Optional.empty());

    Optional<PlanDto> result = service.getActivePlan(householdId, week);

    assertThat(result).isEmpty();
    verifyNoInteractions(planMapper);
  }

  @Test
  void getActivePlan_whenPresent_invokesMapper_andReturnsHydratedDto() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan plan = PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 2, 2);
    PlanDto expected = Mockito.mock(PlanDto.class);
    when(planRepository.findFirstByHouseholdIdAndWeekStartDateAndStatus(
            householdId, week, PlanStatus.ACTIVE))
        .thenReturn(Optional.of(plan));
    when(planMapper.toDto(any(Plan.class))).thenReturn(expected);

    Optional<PlanDto> result = service.getActivePlan(householdId, week);

    assertThat(result).hasValue(expected);
    verify(planMapper).toDto(plan);
  }

  // ---------------- getPlanHistory ----------------

  @Test
  void getPlanHistory_whenEmpty_returnsEmptyList_andSkipsMapper() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
            eq(householdId), eq(week), any(Pageable.class)))
        .thenReturn(Page.empty());

    List<PlanDto> result = service.getPlanHistory(householdId, week);

    assertThat(result).isEmpty();
    verifyNoInteractions(planMapper);
  }

  @Test
  void getPlanHistory_capsRequestAt100() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    when(planRepository.findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
            eq(householdId), eq(week), any(Pageable.class)))
        .thenReturn(Page.empty());

    service.getPlanHistory(householdId, week);

    // HISTORY_CAP locked at 100 in PlannerServiceImpl — package-private constant; assert
    // verbatim via the issued PageRequest rather than reaching into the impl.
    verify(planRepository)
        .findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
            householdId, week, PageRequest.of(0, 100));
  }

  @Test
  void getPlanHistory_whenMultipleGenerations_mapsEach_preservingRepoOrder() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 5, 11);
    Plan gen3 = PlanTestData.newPlanGraph(householdId, week, 3, PlanStatus.ACTIVE, 1, 1);
    Plan gen2 = PlanTestData.newPlanGraph(householdId, week, 2, PlanStatus.SUPERSEDED, 1, 1);
    Plan gen1 = PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.SUPERSEDED, 1, 1);
    when(planRepository.findByHouseholdIdAndWeekStartDateOrderByGenerationDesc(
            eq(householdId), eq(week), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(gen3, gen2, gen1)));
    PlanDto dto3 = Mockito.mock(PlanDto.class);
    PlanDto dto2 = Mockito.mock(PlanDto.class);
    PlanDto dto1 = Mockito.mock(PlanDto.class);
    when(planMapper.toDto(gen3)).thenReturn(dto3);
    when(planMapper.toDto(gen2)).thenReturn(dto2);
    when(planMapper.toDto(gen1)).thenReturn(dto1);

    List<PlanDto> result = service.getPlanHistory(householdId, week);

    assertThat(result).containsExactly(dto3, dto2, dto1);
  }

  // ---------------- getPlansBetween ----------------

  @Test
  void getPlansBetween_whenFromAfterTo_throwsIllegalArgument() {
    UUID householdId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 5, 18);
    LocalDate to = LocalDate.of(2026, 5, 11);
    Pageable pageable = PageRequest.of(0, 20);

    assertThatThrownBy(() -> service.getPlansBetween(householdId, from, to, pageable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be <= to");
    verifyNoInteractions(planRepository);
  }

  @Test
  void getPlansBetween_whenFromEqualsTo_isInclusive_andDelegates() {
    UUID householdId = UUID.randomUUID();
    LocalDate sameDay = LocalDate.of(2026, 5, 11);
    Pageable pageable = PageRequest.of(0, 20);
    when(planRepository
            .findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
                householdId, sameDay, sameDay, pageable))
        .thenReturn(Page.empty(pageable));

    Page<PlanDto> result = service.getPlansBetween(householdId, sameDay, sameDay, pageable);

    assertThat(result).isEmpty();
  }

  @Test
  void getPlansBetween_mapsThroughPlanMapper_andPreservesPageMetadata() {
    UUID householdId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 5, 4);
    LocalDate to = LocalDate.of(2026, 5, 18);
    // PageImpl constructor auto-shrinks `total` when offset+pageSize > total AND content
    // is non-empty; use page=1 (offset=2) so the shrink branch doesn't fire.
    Pageable pageable = PageRequest.of(1, 2);
    Plan plan = PlanTestData.newPlanGraph(householdId, from, 1, PlanStatus.ACTIVE, 1, 1);
    PlanDto dto = Mockito.mock(PlanDto.class);
    when(planRepository
            .findByHouseholdIdAndWeekStartDateBetweenOrderByWeekStartDateDescGenerationDesc(
                householdId, from, to, pageable))
        .thenReturn(new PageImpl<>(List.of(plan), pageable, 7L));
    when(planMapper.toDto(plan)).thenReturn(dto);

    Page<PlanDto> result = service.getPlansBetween(householdId, from, to, pageable);

    assertThat(result.getContent()).containsExactly(dto);
    assertThat(result.getTotalElements()).isEqualTo(7L);
    assertThat(result.getNumber()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(2);
  }

  // ---------------- getPlansByIds ----------------

  @Test
  void getPlansByIds_emptyInput_returnsEmpty_andIssuesNoSql() {
    List<PlanDto> result = service.getPlansByIds(Collections.emptyList());

    assertThat(result).isEmpty();
    verifyNoInteractions(planRepository);
    verifyNoInteractions(planMapper);
  }

  @Test
  void getPlansByIds_nullInput_returnsEmpty_andIssuesNoSql() {
    List<PlanDto> result = service.getPlansByIds(null);

    assertThat(result).isEmpty();
    verifyNoInteractions(planRepository);
    verifyNoInteractions(planMapper);
  }

  @Test
  void getPlansByIds_unknownIds_returnsEmpty() {
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(planRepository.findByIdIn(ids)).thenReturn(Collections.emptyList());

    List<PlanDto> result = service.getPlansByIds(ids);

    assertThat(result).isEmpty();
    verifyNoInteractions(planMapper);
  }

  @Test
  void getPlansByIds_mixedKnownUnknown_returnsOnlyKnown_inRepoOrder() {
    UUID householdId = UUID.randomUUID();
    Plan p1 =
        PlanTestData.newPlanGraph(
            householdId, LocalDate.of(2026, 5, 11), 1, PlanStatus.ACTIVE, 1, 1);
    Plan p2 =
        PlanTestData.newPlanGraph(
            householdId, LocalDate.of(2026, 5, 18), 1, PlanStatus.ACTIVE, 1, 1);
    PlanDto d1 = Mockito.mock(PlanDto.class);
    PlanDto d2 = Mockito.mock(PlanDto.class);
    UUID unknown = UUID.randomUUID();
    List<UUID> input = List.of(p1.getId(), unknown, p2.getId());
    when(planRepository.findByIdIn(input)).thenReturn(List.of(p1, p2));
    when(planMapper.toDto(p1)).thenReturn(d1);
    when(planMapper.toDto(p2)).thenReturn(d2);

    List<PlanDto> result = service.getPlansByIds(input);

    assertThat(result).containsExactly(d1, d2);
  }

  // ---------------- getPendingSuggestions ----------------

  @Test
  void getPendingSuggestions_filtersByPendingStatus_andMaps() {
    UUID householdId = UUID.randomUUID();
    // Use page=1 (offset=2) so PageImpl's auto-shrink branch doesn't fire when content size
    // is smaller than the configured total.
    Pageable pageable = PageRequest.of(1, 2);
    ReoptSuggestion suggestion = Mockito.mock(ReoptSuggestion.class);
    ReoptSuggestionDto dto = Mockito.mock(ReoptSuggestionDto.class);
    when(reoptSuggestionRepository.findByHouseholdIdAndStatusOrderByCreatedAtDesc(
            householdId, ReoptStatus.PENDING, pageable))
        .thenReturn(new PageImpl<>(List.of(suggestion), pageable, 4L));
    when(reoptSuggestionMapper.toDto(suggestion)).thenReturn(dto);

    Page<ReoptSuggestionDto> result = service.getPendingSuggestions(householdId, pageable);

    assertThat(result.getContent()).containsExactly(dto);
    assertThat(result.getTotalElements()).isEqualTo(4L);
    verify(reoptSuggestionRepository)
        .findByHouseholdIdAndStatusOrderByCreatedAtDesc(householdId, ReoptStatus.PENDING, pageable);
  }

  @Test
  void getPendingSuggestions_whenEmpty_returnsEmptyPage_andSkipsMapper() {
    UUID householdId = UUID.randomUUID();
    Pageable pageable = PageRequest.of(0, 20);
    when(reoptSuggestionRepository.findByHouseholdIdAndStatusOrderByCreatedAtDesc(
            householdId, ReoptStatus.PENDING, pageable))
        .thenReturn(Page.empty(pageable));

    Page<ReoptSuggestionDto> result = service.getPendingSuggestions(householdId, pageable);

    assertThat(result).isEmpty();
    verify(reoptSuggestionMapper, never()).toDto(any());
  }

  // ---------------- getSuggestion ----------------

  @Test
  void getSuggestion_unknownId_returnsEmpty() {
    UUID id = UUID.randomUUID();
    when(reoptSuggestionRepository.findById(id)).thenReturn(Optional.empty());

    Optional<ReoptSuggestionDto> result = service.getSuggestion(id);

    assertThat(result).isEmpty();
    verifyNoInteractions(reoptSuggestionMapper);
  }

  @Test
  void getSuggestion_knownId_mapsAndReturns() {
    UUID id = UUID.randomUUID();
    ReoptSuggestion suggestion = Mockito.mock(ReoptSuggestion.class);
    ReoptSuggestionDto dto = Mockito.mock(ReoptSuggestionDto.class);
    when(reoptSuggestionRepository.findById(id)).thenReturn(Optional.of(suggestion));
    when(reoptSuggestionMapper.toDto(suggestion)).thenReturn(dto);

    Optional<ReoptSuggestionDto> result = service.getSuggestion(id);

    assertThat(result).hasValue(dto);
    verify(reoptSuggestionMapper, times(1)).toDto(suggestion);
  }

  // ---------------- getUpcomingSlots (notification/01b) ----------------

  @Test
  void getUpcomingSlots_whenFromAfterTo_throwsIllegalArgument() {
    UUID householdId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.getUpcomingSlots(
                    householdId, LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 15)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(planRepository);
  }

  @Test
  void getUpcomingSlots_noActivePlans_returnsEmpty() {
    UUID householdId = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 6, 15);
    when(planRepository.findByHouseholdIdAndStatusIn(householdId, List.of(PlanStatus.ACTIVE)))
        .thenReturn(Collections.emptyList());

    assertThat(service.getUpcomingSlots(householdId, from, from.plusDays(1))).isEmpty();
  }

  @Test
  void getUpcomingSlots_projectsSlotsWithinWindow_onlyInRange() {
    UUID householdId = UUID.randomUUID();
    LocalDate week = LocalDate.of(2026, 6, 15);
    // 3 days, 2 slots/day → days 06-15, 06-16, 06-17.
    Plan plan = PlanTestData.newPlanGraph(householdId, week, 1, PlanStatus.ACTIVE, 3, 2);
    when(planRepository.findByHouseholdIdAndStatusIn(householdId, List.of(PlanStatus.ACTIVE)))
        .thenReturn(List.of(plan));

    // Window only covers the first two days → 2 days * 2 slots = 4 slot views.
    List<UpcomingSlotView> views = service.getUpcomingSlots(householdId, week, week.plusDays(1));

    assertThat(views).hasSize(4);
    assertThat(views).allSatisfy(v -> assertThat(v.householdId()).isEqualTo(householdId));
    assertThat(views).allSatisfy(v -> assertThat(v.recipeId()).isNotNull());
    assertThat(views).extracting(UpcomingSlotView::dayDate).containsOnly(week, week.plusDays(1));
  }
}
