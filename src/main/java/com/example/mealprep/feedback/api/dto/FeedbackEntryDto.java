package com.example.mealprep.feedback.api.dto;

import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public-facing view of a {@code FeedbackEntry} aggregate. {@code routes} is populated by {@code
 * FeedbackEntryMapper} once routing has started; {@code pendingClarificationQueryId} is filled by
 * the service layer (it requires a repository lookup the mapper cannot do).
 */
public record FeedbackEntryDto(
    UUID id,
    UUID userId,
    String text,
    UiContextDto context,
    SubmissionStatus submissionStatus,
    int classificationAttempts,
    Instant lastClassifiedAt,
    UUID traceId,
    List<RoutingDecisionDto> routes,
    UUID pendingClarificationQueryId,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Returns a copy with {@code pendingClarificationQueryId} replaced. Used by {@code
   * FeedbackServiceImpl.getById} to enrich the DTO after the mapper has produced it — the mapper
   * cannot do the clarification-repo lookup itself.
   */
  public FeedbackEntryDto withPendingClarificationQueryId(UUID pendingId) {
    return new FeedbackEntryDto(
        this.id,
        this.userId,
        this.text,
        this.context,
        this.submissionStatus,
        this.classificationAttempts,
        this.lastClassifiedAt,
        this.traceId,
        this.routes,
        pendingId,
        this.createdAt,
        this.updatedAt);
  }
}
