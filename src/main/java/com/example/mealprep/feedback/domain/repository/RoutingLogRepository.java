package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link RoutingLogEntry}. Package-private; cross-module callers go
 * through {@code FeedbackQueryService}.
 *
 * <p>{@code aggregateByDestination} JPQL projects into {@link DestinationRollupRow}. Spring Data
 * binds positionally for interface projections; if a future column rename breaks the binding, the
 * fix is switching to {@code select new com.example.mealprep.feedback.domain.repository.…(…)}
 * constructor projection (per ticket 01a §22).
 */
interface RoutingLogRepository extends JpaRepository<RoutingLogEntry, UUID> {

  List<RoutingLogEntry> findByFeedbackEntryIdOrderByRoutedAtAsc(UUID entryId);

  Optional<RoutingLogEntry> findByIdAndFeedbackEntryUserId(UUID id, UUID userId);

  /** Quality monitoring rollup (admin endpoint owned later — feedback-01g). */
  @Query(
      """
      select r.destination as destination,
             count(r)       as count,
             avg(r.confidence) as avgConfidence
        from RoutingLogEntry r
       where r.routedAt between :from and :to
       group by r.destination
      """)
  List<DestinationRollupRow> aggregateByDestination(
      @Param("from") Instant from, @Param("to") Instant to);
}
