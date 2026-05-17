package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MisclassificationCorrection}. {@code public} so {@code
 * FeedbackServiceImpl} (which lives in {@code feedback.domain.service} per the LLD style guide) can
 * inject it; the {@code FeedbackBoundaryTest} cross-module-import rule still blocks out-of-module
 * use — cross-module callers go through {@code FeedbackQueryService}.
 */
public interface MisclassificationCorrectionRepository
    extends JpaRepository<MisclassificationCorrection, UUID> {

  Page<MisclassificationCorrection> findByFeedbackEntryUserIdOrderByOccurredAtDesc(
      UUID userId, Pageable pageable);

  long countByOccurredAtBetween(Instant from, Instant to);

  long countByOriginalDestinationAndOccurredAtBetween(
      Destination originalDestination, Instant from, Instant to);
}
