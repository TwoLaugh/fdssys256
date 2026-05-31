package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ClarificationQuery}. Package-private; cross-module callers go
 * through {@code FeedbackQueryService} / {@code FeedbackUpdateService}.
 */
public interface ClarificationQueryRepository extends JpaRepository<ClarificationQuery, UUID> {

  Optional<ClarificationQuery> findByIdAndFeedbackEntryUserId(UUID id, UUID userId);

  Page<ClarificationQuery> findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(
      UUID userId, ClarificationStatus status, Pageable pageable);

  /** Daily expiry sweep — feedback-01e. */
  List<ClarificationQuery> findByStatusAndExpiresAtBefore(
      ClarificationStatus status, Instant before);

  /**
   * Used by {@code FeedbackServiceImpl.getById} to populate {@code
   * FeedbackEntryDto.pendingClarificationQueryId}. By convention there is at most one {@code
   * PENDING} clarification per entry — see feedback-01e for the partial-unique-index follow-up.
   */
  Optional<ClarificationQuery> findFirstByFeedbackEntryIdAndStatus(
      UUID feedbackEntryId, ClarificationStatus status);

  /**
   * Batched sibling of {@link #findFirstByFeedbackEntryIdAndStatus} for {@code getByIds}
   * (feedback-7): one query fetches the {@code (feedbackEntryId, queryId)} pairs for every entry in
   * {@code feedbackEntryIds} that has a clarification in {@code status}, so the service can
   * populate each DTO's {@code pendingClarificationQueryId} without a per-entry round-trip. By
   * convention there is at most one PENDING clarification per entry (see the partial-unique-index
   * follow-up), so each entry id appears at most once.
   */
  @Query(
      "SELECT q.feedbackEntry.id AS feedbackEntryId, q.id AS queryId FROM ClarificationQuery q"
          + " WHERE q.feedbackEntry.id IN :feedbackEntryIds AND q.status = :status")
  List<PendingClarificationRef> findClarificationRefsByFeedbackEntryIdInAndStatus(
      @Param("feedbackEntryIds") Collection<UUID> feedbackEntryIds,
      @Param("status") ClarificationStatus status);

  /** Projection for the batched pending-clarification lookup. */
  interface PendingClarificationRef {
    UUID getFeedbackEntryId();

    UUID getQueryId();
  }

  /**
   * Used by {@code listClarificationQueries} when no {@code status} filter is supplied — the
   * user-facing inbox, oldest first (LLD line 188).
   */
  Page<ClarificationQuery> findByFeedbackEntryUserIdOrderByCreatedAtAsc(
      UUID userId, Pageable pageable);

  /**
   * Used by 01c's {@code FeedbackClassificationListener.buildContext} on the re-classification
   * path: the most-recently answered clarification for an entry carries the user's hint + free-text
   * to feed back into the classifier.
   */
  Optional<ClarificationQuery> findFirstByFeedbackEntryIdAndStatusOrderByAnsweredAtDesc(
      UUID feedbackEntryId, ClarificationStatus status);
}
