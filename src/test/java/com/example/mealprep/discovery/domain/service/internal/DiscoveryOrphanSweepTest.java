package com.example.mealprep.discovery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.repository.DiscoveryJobRepository;
import com.example.mealprep.discovery.domain.repository.DiscoverySourceRepository;
import com.example.mealprep.discovery.domain.service.RobotsTxtGate;
import com.example.mealprep.discovery.event.DiscoveryJobCompletedEvent;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.example.mealprep.preference.domain.service.HardConstraintFilterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit coverage of the orphan-sweep mechanics. Verifies stale RUNNING rows transition to FAILED,
 * fresh ones are untouched, and a JobCompletedEvent fires per sweep.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryOrphanSweepTest {

  @Mock private DiscoveryJobRepository jobRepository;
  @Mock private DiscoverySourceRepository sourceRepository;
  @Mock private SourceRegistry sourceRegistry;
  @Mock private RobotsTxtGate robotsTxtGate;
  @Mock private SourceRateLimiterRegistry rateLimiterRegistry;
  @Mock private CandidateAiFilter candidateAiFilter;
  @Mock private HardConstraintFilterService hardConstraintFilter;
  @Mock private DiscoveryJobTransitions transitions;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private com.example.mealprep.recipe.spi.RecipeWriteApi recipeWriteApi;

  private DiscoveryJobRunner runner;

  @BeforeEach
  void setUp() {
    DiscoveryProperties properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10),
            30,
            Duration.ofSeconds(60),
            Duration.ofHours(1),
            Duration.ofHours(6),
            null);
    runner =
        new DiscoveryJobRunner(
            jobRepository,
            sourceRepository,
            sourceRegistry,
            robotsTxtGate,
            rateLimiterRegistry,
            new ContentFingerprintHasher(),
            candidateAiFilter,
            hardConstraintFilter,
            transitions,
            eventPublisher,
            properties,
            new ObjectMapper(),
            recipeWriteApi);
  }

  @Test
  void sweepOrphansNow_zeroOrphans_returnsZero() {
    when(jobRepository.findOrphanRunning(any())).thenReturn(List.of());
    OrphanSweepResultDto result = runner.sweepOrphansNow();
    assertThat(result.resumedCount()).isZero();
    verify(transitions, never()).finaliseTo(any(), any(), any(), any(), any());
  }

  @Test
  void sweepOrphansNow_oneOrphan_finalisesFailed_publishesCompletedEvent() {
    UUID jobId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    DiscoveryJob orphan = DiscoveryTestData.sampleJob(userId);
    orphan.setId(jobId);
    orphan.setStatus(DiscoveryJobStatus.RUNNING);
    orphan.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));

    when(jobRepository.findOrphanRunning(any())).thenReturn(List.of(orphan));
    when(transitions.finaliseTo(eq(jobId), eq(DiscoveryJobStatus.FAILED), any(), any(), any()))
        .thenReturn(Optional.of(orphan));
    // The runner reads the refreshed row for the event payload.
    lenient().when(jobRepository.findById(jobId)).thenReturn(Optional.of(orphan));

    OrphanSweepResultDto result = runner.sweepOrphansNow();

    assertThat(result.resumedCount()).isEqualTo(1);
    verify(transitions)
        .finaliseTo(
            eq(jobId),
            eq(DiscoveryJobStatus.FAILED),
            eq("runner crashed; resumed by sweep"),
            anyList(),
            anyList());
    verify(eventPublisher, times(1)).publishEvent(any(DiscoveryJobCompletedEvent.class));
  }

  @Test
  void sweepOrphansNow_finaliseReturnsEmpty_doesNotIncrementCount() {
    UUID jobId = UUID.randomUUID();
    DiscoveryJob orphan = DiscoveryTestData.sampleJob(UUID.randomUUID());
    orphan.setId(jobId);
    orphan.setStatus(DiscoveryJobStatus.RUNNING);
    orphan.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));

    when(jobRepository.findOrphanRunning(any())).thenReturn(List.of(orphan));
    when(transitions.finaliseTo(eq(jobId), eq(DiscoveryJobStatus.FAILED), any(), any(), any()))
        .thenReturn(Optional.empty());

    OrphanSweepResultDto result = runner.sweepOrphansNow();

    assertThat(result.resumedCount()).isZero();
    verify(eventPublisher, never()).publishEvent(any(DiscoveryJobCompletedEvent.class));
  }
}
