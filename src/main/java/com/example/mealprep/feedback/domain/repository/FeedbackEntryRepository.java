package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link FeedbackEntry}. Package-private; cross-module callers go
 * through {@code FeedbackQueryService} / {@code FeedbackUpdateService} (landing in feedback-01b).
 *
 * <p>{@code @EntityGraph} on {@code findWithRoutingByIdAndUserId} keeps the per-entry detail load
 * to a single LEFT JOIN — the routing log is always read together with the entry on the
 * confirmation view. The page-list query does <i>not</i> eager-load the log; the timeline view
 * renders a pill summary only.
 */
public interface FeedbackEntryRepository extends JpaRepository<FeedbackEntry, UUID> {

  @EntityGraph(attributePaths = {"routingLog"})
  Optional<FeedbackEntry> findWithRoutingByIdAndUserId(UUID id, UUID userId);

  Page<FeedbackEntry> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  /** Async sweep: anything stuck in CLASSIFYING beyond the threshold is retried by 01g. */
  List<FeedbackEntry> findBySubmissionStatusInAndCreatedAtBefore(
      Collection<SubmissionStatus> statuses, Instant before);

  /**
   * Native UPDATE for the submission-status flip. Bypasses Hibernate's full-entity dirty-check
   * + @Version optimistic locking which races with the publisher's persistence context when the
   * AFTER_COMMIT listener fires (round-8 retro: native UPDATE pattern). The version column is
   * bumped here so subsequent JPA reads observe the new state without stale-version errors.
   */
  @Modifying
  @Query(
      "UPDATE FeedbackEntry e SET e.submissionStatus = :status, e.version = e.version + 1 WHERE"
          + " e.id = :id")
  int updateSubmissionStatus(@Param("id") UUID id, @Param("status") SubmissionStatus status);

  /** Native UPDATE for status + attempts increment in one tx (the CLASSIFYING-start path). */
  @Modifying
  @Query(
      "UPDATE FeedbackEntry e SET e.submissionStatus = :status, e.classificationAttempts ="
          + " e.classificationAttempts + 1, e.version = e.version + 1 WHERE e.id = :id")
  int updateSubmissionStatusAndIncrementAttempts(
      @Param("id") UUID id, @Param("status") SubmissionStatus status);

  /** Native UPDATE for the revert-to-RECEIVED path (decrement attempts, floor at 0). */
  @Modifying
  @Query(
      "UPDATE FeedbackEntry e SET e.submissionStatus = :status, e.classificationAttempts ="
          + " CASE WHEN e.classificationAttempts > 0 THEN e.classificationAttempts - 1 ELSE 0"
          + " END, e.version = e.version + 1 WHERE e.id = :id")
  int updateSubmissionStatusAndDecrementAttempts(
      @Param("id") UUID id, @Param("status") SubmissionStatus status);

  /** Native UPDATE that also stamps lastClassifiedAt (terminal paths). */
  @Modifying
  @Query(
      "UPDATE FeedbackEntry e SET e.submissionStatus = :status, e.lastClassifiedAt = :at,"
          + " e.version = e.version + 1 WHERE e.id = :id")
  int updateSubmissionStatusAndLastClassifiedAt(
      @Param("id") UUID id, @Param("status") SubmissionStatus status, @Param("at") Instant at);
}
