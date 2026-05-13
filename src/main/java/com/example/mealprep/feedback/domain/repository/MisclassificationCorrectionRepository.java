package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MisclassificationCorrection}. Package-private; cross-module
 * callers go through {@code FeedbackQueryService}.
 */
interface MisclassificationCorrectionRepository
    extends JpaRepository<MisclassificationCorrection, UUID> {

  Page<MisclassificationCorrection> findByFeedbackEntryUserIdOrderByOccurredAtDesc(
      UUID userId, Pageable pageable);

  long countByOccurredAtBetween(Instant from, Instant to);

  long countByOriginalDestinationAndOccurredAtBetween(
      Destination originalDestination, Instant from, Instant to);
}
