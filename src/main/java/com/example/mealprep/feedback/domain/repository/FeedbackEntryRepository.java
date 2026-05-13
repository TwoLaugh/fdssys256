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
}
