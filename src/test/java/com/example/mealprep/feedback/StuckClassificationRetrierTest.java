package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.config.FeedbackRetrySweepProperties;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.service.internal.StuckClassificationRetrier;
import com.example.mealprep.feedback.event.FeedbackProcessedEvent;
import com.example.mealprep.feedback.event.FeedbackSubmittedEvent;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for the per-entry retry/escalate decision (feedback-01i). Pure Mockito — the {@code
 * REQUIRES_NEW} boundary is a Spring proxy concern exercised by {@code FeedbackAsyncSweepIT}; here
 * we verify the branch logic against a fixed {@link Clock}.
 *
 * <p>Branches: escalate-to-FAILED past the 24h gate (terminal {@link FeedbackProcessedEvent}),
 * re-classify within 24h (re-published {@link FeedbackSubmittedEvent}, same traceId), and the
 * idempotent no-op when the entry raced to a non-retryable status. The 24h boundary is tested
 * deterministically via {@code Clock.fixed}.
 */
@ExtendWith(MockitoExtension.class)
class StuckClassificationRetrierTest {

  @Mock private FeedbackEntryRepository entryRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-23T12:00:00Z"), ZoneOffset.UTC);
  private final FeedbackRetrySweepProperties properties =
      new FeedbackRetrySweepProperties(null, null, null); // 5-min stuck / 24h escalate defaults.

  private StuckClassificationRetrier retrier() {
    return new StuckClassificationRetrier(entryRepository, eventPublisher, properties, clock);
  }

  private FeedbackEntry stuckEntry(
      SubmissionStatus status, Instant createdAt, Instant lastClassAt) {
    FeedbackEntry e = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "the salt was too much");
    e.setSubmissionStatus(status);
    e.setCreatedAt(createdAt);
    e.setLastClassifiedAt(lastClassAt);
    return e;
  }

  @Test
  void retryOne_receivedOlderThan24h_escalatesToFailed_publishesTerminalEvent() {
    Instant createdAt = clock.instant().minus(Duration.ofHours(25)); // > 24h
    FeedbackEntry entry = stuckEntry(SubmissionStatus.RECEIVED, createdAt, null);
    when(entryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
    when(entryRepository.updateSubmissionStatusAndLastClassifiedAt(
            entry.getId(), SubmissionStatus.FAILED, clock.instant()))
        .thenReturn(1);

    boolean escalated = retrier().retryOne(entry.getId());

    assertThat(escalated).isTrue();
    verify(entryRepository)
        .updateSubmissionStatusAndLastClassifiedAt(
            entry.getId(), SubmissionStatus.FAILED, clock.instant());
    ArgumentCaptor<FeedbackProcessedEvent> ec =
        ArgumentCaptor.forClass(FeedbackProcessedEvent.class);
    verify(eventPublisher).publishEvent(ec.capture());
    FeedbackProcessedEvent ev = ec.getValue();
    assertThat(ev.feedbackId()).isEqualTo(entry.getId());
    assertThat(ev.userId()).isEqualTo(entry.getUserId());
    assertThat(ev.partialFailure()).isTrue(); // terminal failure flag
    assertThat(ev.clarificationPending()).isFalse();
    assertThat(ev.destinationsTouched()).isEmpty();
    assertThat(ev.traceId()).isEqualTo(entry.getTraceId());
  }

  @Test
  void retryOne_receivedWithin24h_reClassifies_republishesSubmittedEvent_sameTraceId() {
    Instant createdAt = clock.instant().minus(Duration.ofMinutes(10)); // > 5min stuck, < 24h
    FeedbackEntry entry = stuckEntry(SubmissionStatus.RECEIVED, createdAt, null);
    when(entryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

    boolean escalated = retrier().retryOne(entry.getId());

    assertThat(escalated).isFalse();
    // No status write on the retry branch — the async listener owns the CLASSIFYING flip.
    verify(entryRepository, never()).updateSubmissionStatusAndLastClassifiedAt(any(), any(), any());
    ArgumentCaptor<FeedbackSubmittedEvent> ec =
        ArgumentCaptor.forClass(FeedbackSubmittedEvent.class);
    verify(eventPublisher).publishEvent(ec.capture());
    FeedbackSubmittedEvent ev = ec.getValue();
    assertThat(ev.feedbackId()).isEqualTo(entry.getId());
    assertThat(ev.userId()).isEqualTo(entry.getUserId());
    // SAME traceId across attempts — keeps the decision log linked (NOT a fresh UUID).
    assertThat(ev.traceId()).isEqualTo(entry.getTraceId());
    assertThat(ev.screen()).isEqualTo(entry.getUiContext().screen());
  }

  @Test
  void retryOne_classifyingStuck_reClassifies() {
    // A crashed-worker entry left in CLASSIFYING, retry clock 10 min ago, well within 24h.
    Instant lastClass = clock.instant().minus(Duration.ofMinutes(10));
    FeedbackEntry entry =
        stuckEntry(
            SubmissionStatus.CLASSIFYING, clock.instant().minus(Duration.ofMinutes(11)), lastClass);
    when(entryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

    boolean escalated = retrier().retryOne(entry.getId());

    assertThat(escalated).isFalse();
    verify(eventPublisher).publishEvent(any(FeedbackSubmittedEvent.class));
  }

  @Test
  void retryOne_exactly24hMinusEpsilon_retried_24hPlusEpsilon_escalated() {
    // Boundary: createdAt at exactly the escalate threshold minus 1ms → retried (not escalated);
    // plus 1ms past → escalated. Deterministic via Clock.fixed.
    Instant justUnder = clock.instant().minus(Duration.ofHours(24)).plusMillis(1);
    FeedbackEntry under = stuckEntry(SubmissionStatus.RECEIVED, justUnder, null);
    when(entryRepository.findById(under.getId())).thenReturn(Optional.of(under));

    assertThat(retrier().retryOne(under.getId())).isFalse(); // retried
    verify(eventPublisher).publishEvent(any(FeedbackSubmittedEvent.class));

    Instant justOver = clock.instant().minus(Duration.ofHours(24)).minusMillis(1);
    FeedbackEntry over = stuckEntry(SubmissionStatus.RECEIVED, justOver, null);
    when(entryRepository.findById(over.getId())).thenReturn(Optional.of(over));
    when(entryRepository.updateSubmissionStatusAndLastClassifiedAt(
            eq(over.getId()), eq(SubmissionStatus.FAILED), any()))
        .thenReturn(1);

    assertThat(retrier().retryOne(over.getId())).isTrue(); // escalated
    verify(eventPublisher).publishEvent(any(FeedbackProcessedEvent.class));
  }

  @Test
  void retryOne_entryRacedToNonRetryableStatus_isNoop() {
    // Already ROUTED between the sweep query and this REQUIRES_NEW re-read → idempotent no-op.
    FeedbackEntry entry =
        stuckEntry(SubmissionStatus.ROUTED, clock.instant().minus(Duration.ofHours(25)), null);
    when(entryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

    boolean escalated = retrier().retryOne(entry.getId());

    assertThat(escalated).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
    verify(entryRepository, never()).updateSubmissionStatusAndLastClassifiedAt(any(), any(), any());
  }

  @Test
  void retryOne_parserFailedEntryAlreadyFailed_isNoop() {
    // A terminal AiInvalidResponseException entry is already FAILED → status filter excludes it;
    // even if handed in directly the retrier never re-classifies it.
    FeedbackEntry entry =
        stuckEntry(SubmissionStatus.FAILED, clock.instant().minus(Duration.ofMinutes(10)), null);
    when(entryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

    boolean escalated = retrier().retryOne(entry.getId());

    assertThat(escalated).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void retryOne_entryVanished_isNoop() {
    UUID gone = UUID.randomUUID();
    when(entryRepository.findById(gone)).thenReturn(Optional.empty());

    assertThat(retrier().retryOne(gone)).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
  }
}
