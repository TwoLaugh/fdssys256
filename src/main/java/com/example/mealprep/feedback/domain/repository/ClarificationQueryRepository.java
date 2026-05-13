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
interface ClarificationQueryRepository extends JpaRepository<ClarificationQuery, UUID> {

  Optional<ClarificationQuery> findByIdAndFeedbackEntryUserId(UUID id, UUID userId);

  Page<ClarificationQuery> findByFeedbackEntryUserIdAndStatusOrderByCreatedAtAsc(
      UUID userId, ClarificationStatus status, Pageable pageable);

  /** Daily expiry sweep — feedback-01e. */
  List<ClarificationQuery> findByStatusAndExpiresAtBefore(
      ClarificationStatus status, Instant before);
}
