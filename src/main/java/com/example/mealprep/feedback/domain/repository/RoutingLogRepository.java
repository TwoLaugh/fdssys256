package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.spi.Destination;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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
public interface RoutingLogRepository extends JpaRepository<RoutingLogEntry, UUID> {

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

  /**
   * Batch-gather query for the AI delta-generation pipeline (preference-01g): the user's
   * routing-log rows for {@code destination}, routed strictly after {@code routedAfter},
   * oldest-first, capped by {@code pageable}. Eager-loads the {@code feedbackEntry} so the
   * orchestrator can read the verbatim feedback text + UI context without a lazy-load outside the
   * tx. {@code routedAfter} is the floor of the EPOCH when no cursor exists (i.e. all of the user's
   * rows for that destination).
   */
  @EntityGraph(attributePaths = {"feedbackEntry"})
  @Query(
      """
      select r from RoutingLogEntry r
       where r.feedbackEntry.userId = :userId
         and r.destination = :destination
         and r.routedAt > :routedAfter
       order by r.routedAt asc
      """)
  List<RoutingLogEntry> findRoutedForUserSince(
      @Param("userId") UUID userId,
      @Param("destination") Destination destination,
      @Param("routedAfter") Instant routedAfter,
      Pageable pageable);
}
