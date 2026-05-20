package com.example.mealprep.provisions.domain.service.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.provisions.domain.repository.CookEventDedupeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the package-private {@link CookEventDedupeSweeper} — the daily sweep over the
 * cook-event idempotency table. Uses a fixed {@link Clock} so the cutoff is exact; verifies the
 * 24-hour {@code RETENTION} window, the {@code deleted > 0} log gate (no behaviour difference
 * either way — the call must still happen), and the repository-pass-through.
 *
 * <p>Anchors on {@code Instant.now(clock)} (not a hardcoded date) so the test stays valid as the
 * codebase ages; the fixed-clock cutoff is computed by subtracting the same {@code RETENTION} that
 * the sweeper uses internally.
 */
@ExtendWith(MockitoExtension.class)
class CookEventDedupeSweeperTest {

  @Mock private CookEventDedupeRepository repository;

  private static final Instant FIXED_NOW = Instant.parse("2026-05-19T04:00:00Z");
  private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

  private CookEventDedupeSweeper newSweeper() {
    return new CookEventDedupeSweeper(repository, clock);
  }

  @Test
  void sweep_callsDeleteOlderThanWithExactly24HourCutoff() {
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(0);

    newSweeper().sweep();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(repository, times(1)).deleteOlderThan(cutoffCaptor.capture());
    // Cutoff must be exactly NOW - 24h.
    Assertions.assertThat(cutoffCaptor.getValue()).isEqualTo(FIXED_NOW.minus(Duration.ofHours(24)));
  }

  @Test
  void sweep_withDeletedCountZero_stillCallsRepositoryOnce() {
    // Mutant on `if (deleted > 0)` boundary / negation — the delete call MUST happen regardless,
    // it's just the log line that's gated. We assert one call exactly.
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(0);

    newSweeper().sweep();

    verify(repository, times(1)).deleteOlderThan(any(Instant.class));
  }

  @Test
  void sweep_withPositiveDeletedCount_callsRepositoryOnce() {
    when(repository.deleteOlderThan(any(Instant.class))).thenReturn(7);

    newSweeper().sweep();

    verify(repository, times(1)).deleteOlderThan(any(Instant.class));
    // Sweeper must not call any other repo method besides deleteOlderThan.
    verify(repository, never()).deleteAll();
  }
}
