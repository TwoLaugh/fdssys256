package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.nutrition.api.controller.IntakeController;
import com.example.mealprep.nutrition.api.dto.IntakeListFilter;
import com.example.mealprep.nutrition.api.dto.IntakeSlotSearchResultDto;
import com.example.mealprep.nutrition.domain.entity.IntakeSlotStatus;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import com.example.mealprep.nutrition.domain.service.NutritionQueryService;
import com.example.mealprep.nutrition.domain.service.NutritionUpdateService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Direct-invocation unit coverage for {@link IntakeController#search} (C-B-048). The endpoint
 * composes an {@link IntakeListFilter}, builds a sorted {@link Pageable}, and delegates to {@code
 * NutritionQueryService.searchIntakeSlots}. We assert:
 *
 * <ul>
 *   <li>The filter forwards the three query params verbatim.
 *   <li>The Pageable carries the {@code onDate DESC, id ASC} stable tiebreaker.
 *   <li>401 when no current user.
 *   <li>The returned page is propagated unchanged.
 * </ul>
 *
 * <p>End-to-end HTTP behaviour (incl. validation 400s) is covered by Spring's built-in
 * {@code @RequestParam} / {@code @Size} binding — out-of-scope here.
 */
@ExtendWith(MockitoExtension.class)
class IntakeSearchControllerUnitTest {

  @Mock private NutritionQueryService queryService;
  @Mock private NutritionUpdateService updateService;
  @Mock private CurrentUserResolver currentUserResolver;

  @InjectMocks private IntakeController controller;

  @Test
  void search_returns401_whenAnonymous() {
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.search(null, null, null, 0, 20))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void search_composesFilterAndPageable_andReturnsServicePage() {
    UUID userId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));

    IntakeSlotSearchResultDto sample =
        new IntakeSlotSearchResultDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            LocalDate.of(2026, 5, 9),
            MealSlot.DINNER,
            IntakeSlotStatus.OVERRIDDEN,
            recipeId,
            "spicy chicken bowl");
    Page<IntakeSlotSearchResultDto> servicePage =
        new PageImpl<>(List.of(sample), PageRequest.of(0, 20), 1L);

    ArgumentCaptor<IntakeListFilter> filterCaptor = ArgumentCaptor.forClass(IntakeListFilter.class);
    ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
    when(queryService.searchIntakeSlots(eq(userId), any(), any())).thenReturn(servicePage);

    Page<IntakeSlotSearchResultDto> result =
        controller.search(recipeId, MealSlot.DINNER, "chicken", 1, 25);

    assertThat(result).isSameAs(servicePage);
    verify(queryService)
        .searchIntakeSlots(eq(userId), filterCaptor.capture(), pageableCaptor.capture());

    IntakeListFilter filter = filterCaptor.getValue();
    assertThat(filter.plannedRecipeId()).isEqualTo(recipeId);
    assertThat(filter.mealSlot()).isEqualTo(MealSlot.DINNER);
    assertThat(filter.q()).isEqualTo("chicken");

    Pageable pageable = pageableCaptor.getValue();
    assertThat(pageable.getPageNumber()).isEqualTo(1);
    assertThat(pageable.getPageSize()).isEqualTo(25);
    Sort sort = pageable.getSort();
    Sort.Order onDate = sort.getOrderFor("intakeDay.onDate");
    Sort.Order idOrder = sort.getOrderFor("id");
    assertThat(onDate).isNotNull();
    assertThat(onDate.getDirection()).isEqualTo(Sort.Direction.DESC);
    assertThat(idOrder).isNotNull();
    assertThat(idOrder.getDirection()).isEqualTo(Sort.Direction.ASC);
  }

  @Test
  void search_allowsNullFilterComponents() {
    UUID userId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(userId));
    Page<IntakeSlotSearchResultDto> empty = Page.empty(PageRequest.of(0, 20));
    when(queryService.searchIntakeSlots(eq(userId), any(), any())).thenReturn(empty);

    Page<IntakeSlotSearchResultDto> result = controller.search(null, null, null, 0, 20);

    assertThat(result.getTotalElements()).isZero();
    ArgumentCaptor<IntakeListFilter> filterCaptor = ArgumentCaptor.forClass(IntakeListFilter.class);
    verify(queryService).searchIntakeSlots(eq(userId), filterCaptor.capture(), any());
    IntakeListFilter f = filterCaptor.getValue();
    assertThat(f.plannedRecipeId()).isNull();
    assertThat(f.mealSlot()).isNull();
    assertThat(f.q()).isNull();
    assertThat(f.hasQuery()).isFalse();
  }
}
