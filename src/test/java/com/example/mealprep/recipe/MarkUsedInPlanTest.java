package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import com.example.mealprep.recipe.domain.service.internal.RecipeServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link RecipeUpdateService#markUsedInPlan(java.util.List)}. Verifies empty-list
 * no-op, multi-ID bulk delegation, unknown-ID tolerance, and that no event is published. The
 * service-impl constructor takes many dependencies — they're nulled here since {@code
 * markUsedInPlan} only touches {@code recipeRepository} + {@code clock}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkUsedInPlanTest {

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-12T03:30:00Z"), ZoneOffset.UTC);

  private RecipeServiceImpl newService(RecipeRepository repo, ApplicationEventPublisher events) {
    return new RecipeServiceImpl(
        repo,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        events,
        fixedClock);
  }

  @Test
  void markUsedInPlan_emptyList_noOp() {
    RecipeRepository repo = org.mockito.Mockito.mock(RecipeRepository.class);
    ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    RecipeServiceImpl svc = newService(repo, events);

    svc.markUsedInPlan(List.of());

    verify(repo, never()).touchLastUsedInPlan(any(), any());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void markUsedInPlan_nullList_noOp() {
    RecipeRepository repo = org.mockito.Mockito.mock(RecipeRepository.class);
    ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    RecipeServiceImpl svc = newService(repo, events);

    svc.markUsedInPlan(null);

    verify(repo, never()).touchLastUsedInPlan(any(), any());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void markUsedInPlan_multipleIds_delegatesToRepo_withClockNow_andPublishesNoEvent() {
    RecipeRepository repo = org.mockito.Mockito.mock(RecipeRepository.class);
    ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    RecipeServiceImpl svc = newService(repo, events);

    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(repo.touchLastUsedInPlan(any(Collection.class), any(Instant.class))).thenReturn(3);

    svc.markUsedInPlan(ids);

    ArgumentCaptor<Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(repo, times(1)).touchLastUsedInPlan(idsCaptor.capture(), eq(fixedClock.instant()));
    assertThat(idsCaptor.getValue()).containsExactlyElementsOf(ids);
    verify(events, never()).publishEvent(any());
  }

  @Test
  void markUsedInPlan_unknownIds_returnsZeroUpdated_doesNotThrow() {
    RecipeRepository repo = org.mockito.Mockito.mock(RecipeRepository.class);
    ApplicationEventPublisher events = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
    RecipeServiceImpl svc = newService(repo, events);

    when(repo.touchLastUsedInPlan(any(Collection.class), any(Instant.class))).thenReturn(0);

    // Should not throw — bulk update tolerates unknown ids per ticket §57.
    svc.markUsedInPlan(List.of(UUID.randomUUID()));

    verify(repo).touchLastUsedInPlan(any(), any());
    verify(events, never()).publishEvent(any());
  }
}
