package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.FeedbackBridgeIdempotency;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link FeedbackBridgeIdempotency}. {@code public} so the in-module
 * {@code feedback.bridge} package can inject it; cross-module isolation is enforced by {@code
 * FeedbackBoundaryTest}.
 *
 * <p>The {@link #insertIfAbsent} native upsert is the idempotency primitive: a {@code DISPATCHED} /
 * {@code REJECTED_LOW_CONFIDENCE} / {@code FAILED} row is inserted unless one already exists for
 * the {@code (feedback_id, destination)} pair (the unique constraint). The returned row-count tells
 * the bridge whether it claimed the slot (1) or a concurrent / earlier invocation already did (0).
 */
public interface FeedbackBridgeIdempotencyRepository
    extends JpaRepository<FeedbackBridgeIdempotency, UUID> {

  /**
   * Insert a new idempotency row, or do nothing if a row for {@code (feedback_id, destination)}
   * already exists. Returns the number of rows inserted (1 = claimed, 0 = already present). Native
   * because JPA has no portable {@code ON CONFLICT DO NOTHING}.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO feedback_bridge_idempotency (id, feedback_id, destination, status,"
              + " dispatched_at) VALUES (:id, :feedbackId, :destination, :status, :dispatchedAt)"
              + " ON CONFLICT (feedback_id, destination) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") UUID id,
      @Param("feedbackId") UUID feedbackId,
      @Param("destination") String destination,
      @Param("status") String status,
      @Param("dispatchedAt") Instant dispatchedAt);

  /**
   * The current row for a {@code (feedback_id, destination)} pair, if any. Used by the bridge to
   * decide whether the 5-minute idempotency window is still open and to update the status after
   * dispatch.
   */
  Optional<FeedbackBridgeIdempotency> findByFeedbackIdAndDestination(
      UUID feedbackId, com.example.mealprep.feedback.spi.Destination destination);

  /** Status update after the bridge resolves the destination call's outcome. */
  @Modifying
  @Query(
      "UPDATE FeedbackBridgeIdempotency r SET r.status = :status, r.dispatchedAt = :dispatchedAt"
          + " WHERE r.feedbackId = :feedbackId AND r.destination = :destination")
  int updateStatus(
      @Param("feedbackId") UUID feedbackId,
      @Param("destination") com.example.mealprep.feedback.spi.Destination destination,
      @Param("status") com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus status,
      @Param("dispatchedAt") Instant dispatchedAt);

  /** Retention sweep — delete rows older than the cutoff. Returns the count deleted. */
  @Modifying
  @Query("DELETE FROM FeedbackBridgeIdempotency r WHERE r.dispatchedAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
