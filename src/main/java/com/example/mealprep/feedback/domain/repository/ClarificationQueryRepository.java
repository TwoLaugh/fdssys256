package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
