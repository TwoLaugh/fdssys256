package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.service.internal.ArchiveEligibilityScanner;
import com.example.mealprep.recipe.event.ArchiveCause;
import com.example.mealprep.recipe.event.RecipeArchivedEvent;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link ArchiveEligibilityScanner}. Verify the batching math (1000 cap, 100-chunk
 * batches), cutoff arithmetic (now - 90 days), empty-eligibility no-op, and per-batch event
 * publication. The class is package-private; reflection instantiates it.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveEligibilityScannerTest {

  @Mock private RecipeRepository recipeRepository;
  @Mock private ApplicationEventPublisher events;

  private Clock fixedClock;
  private ArchiveEligibilityScanner scanner;

  @BeforeEach
  void setUp() throws Exception {
    fixedClock = Clock.fixed(Instant.parse("2026-05-12T03:30:00Z"), ZoneOffset.UTC);
    scanner = newScanner(recipeRepository, events, fixedClock);
  }

  private static ArchiveEligibilityScanner newScanner(
      RecipeRepository repo, ApplicationEventPublisher events, Clock clock) throws Exception {
    Constructor<ArchiveEligibilityScanner> ctor =
        ArchiveEligibilityScanner.class.getDeclaredConstructor(
            RecipeRepository.class, ApplicationEventPublisher.class, Clock.class);
    ctor.setAccessible(true);
    return ctor.newInstance(repo, events, clock);
  }

  @Test
  void runOnce_emptyEligibility_returnsZero_andPublishesNoEvents() {
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    int flagged = scanner.runOnce();

    assertThat(flagged).isZero();
    verify(events, never()).publishEvent(any());
    verify(recipeRepository, never()).markArchived(any(), any());
  }

  @Test
  void runOnce_singleBatch_archivesAndPublishesOnePerId() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      ids.add(UUID.randomUUID());
    }
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(ids);
    when(recipeRepository.markArchived(any(), any(Instant.class))).thenReturn(5);

    int flagged = scanner.runOnce();

    assertThat(flagged).isEqualTo(5);
    verify(recipeRepository, times(1)).markArchived(any(), any(Instant.class));
    ArgumentCaptor<RecipeArchivedEvent> captor = ArgumentCaptor.forClass(RecipeArchivedEvent.class);
    verify(events, times(5)).publishEvent(captor.capture());
    for (RecipeArchivedEvent ev : captor.getAllValues()) {
      assertThat(ev.cause()).isEqualTo(ArchiveCause.INACTIVITY_3_MONTHS);
      assertThat(ids).contains(ev.recipeId());
      assertThat(ev.occurredAt()).isEqualTo(fixedClock.instant());
    }
  }

  @Test
  void runOnce_thresholdChunks100() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 250; i++) {
      ids.add(UUID.randomUUID());
    }
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(ids);
    when(recipeRepository.markArchived(any(), any(Instant.class))).thenReturn(100, 100, 50);

    int flagged = scanner.runOnce();

    assertThat(flagged).isEqualTo(250);
    // 250 / 100 = 3 batches (100, 100, 50).
    verify(recipeRepository, times(3)).markArchived(any(), any(Instant.class));
    verify(events, times(250)).publishEvent(any(RecipeArchivedEvent.class));
  }

  @Test
  void runOnce_passesCutoff_90DaysBeforeNow_andMaxPerRun1000() {
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());

    scanner.runOnce();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(recipeRepository)
        .findArchiveEligibleSystemRecipes(cutoffCaptor.capture(), pageCaptor.capture());
    Instant expected = fixedClock.instant().minusSeconds(90L * 24 * 60 * 60);
    assertThat(cutoffCaptor.getValue()).isEqualTo(expected);
    assertThat(pageCaptor.getValue()).isEqualTo(PageRequest.of(0, 1000));
  }

  @Test
  void runOnce_markArchivedReturningLowerCount_isStillSummed() {
    // Race condition: bulk update sees 5 IDs but only 3 still satisfy `archived_at is null`. The
    // scanner publishes one event per supplied id (10 events for 10 ids), but flaggedCount sums the
    // actual update counts (matching the SQL).
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(ids);
    when(recipeRepository.markArchived(any(Collection.class), any(Instant.class))).thenReturn(2);

    int flagged = scanner.runOnce();

    assertThat(flagged).isEqualTo(2);
    verify(events, times(3)).publishEvent(any(RecipeArchivedEvent.class));
  }

  @Test
  void runScheduled_delegatesTo_runOnce_andReturnsResult() {
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(List.of());
    int n = scanner.runScheduled();
    assertThat(n).isEqualTo(0);
    verify(recipeRepository).findArchiveEligibleSystemRecipes(any(), eq(PageRequest.of(0, 1000)));
  }

  @Test
  void runScheduled_propagatesNonZeroCountFromRunOnce() {
    // runScheduled returns runOnce()'s result verbatim; a non-zero return kills the
    // "replaced int return with 0" mutant on runScheduled (L57).
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      ids.add(UUID.randomUUID());
    }
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(ids);
    when(recipeRepository.markArchived(any(), any(Instant.class))).thenReturn(4);

    assertThat(scanner.runScheduled()).isEqualTo(4);
  }

  @Test
  void runOnce_exactMultipleOfBatch_doesNotRunAnEmptyTrailingBatch() {
    // 200 ids = exactly 2 batches of 100. The loop guard `i < ids.size()` must stop at i=200; a
    // `<=` boundary mutant (L78) would run a 3rd iteration with an empty subList(200,200).
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      ids.add(UUID.randomUUID());
    }
    when(recipeRepository.findArchiveEligibleSystemRecipes(any(Instant.class), any(Pageable.class)))
        .thenReturn(ids);
    when(recipeRepository.markArchived(any(), any(Instant.class))).thenReturn(100);

    int flagged = scanner.runOnce();

    assertThat(flagged).isEqualTo(200);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<UUID>> chunkCaptor = ArgumentCaptor.forClass(List.class);
    verify(recipeRepository, times(2)).markArchived(chunkCaptor.capture(), any(Instant.class));
    // Every persisted chunk must be a full, non-empty 100-id batch — no trailing empty chunk.
    for (List<UUID> chunk : chunkCaptor.getAllValues()) {
      assertThat(chunk).hasSize(100);
    }
    verify(events, times(200)).publishEvent(any(RecipeArchivedEvent.class));
  }
}
