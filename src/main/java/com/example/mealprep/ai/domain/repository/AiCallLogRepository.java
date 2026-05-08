package com.example.mealprep.ai.domain.repository;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import com.example.mealprep.ai.domain.entity.CallStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link AiCallLog}. Package-private at the package level — cross-module
 * callers go through {@code AiService} (which writes) and {@code AiCostTrackingService} (which
 * reads aggregates).
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {

  /**
   * Sum of {@code cost_micro_pence} for {@link CallStatus#SUCCEEDED} rows belonging to {@code
   * userId} created strictly after {@code since}. Returns {@code 0} (never {@code null}) when no
   * rows match — JPQL's {@code COALESCE} keeps the budget guard's arithmetic uncomplicated.
   */
  @Query(
      """
      SELECT COALESCE(SUM(c.costMicroPence), 0)
      FROM AiCallLog c
      WHERE c.userId = :userId
        AND c.status = com.example.mealprep.ai.domain.entity.CallStatus.SUCCEEDED
        AND c.createdAt > :since
      """)
  long sumCostMicroPenceForUserSince(@Param("userId") UUID userId, @Param("since") Instant since);

  /**
   * Per-task-type cost breakdown for the same window — feeds {@code AiCostTrackingService} admin
   * reports. Each row is {@code [TaskType, sum]}.
   */
  @Query(
      """
      SELECT c.taskType, COALESCE(SUM(c.costMicroPence), 0)
      FROM AiCallLog c
      WHERE c.userId = :userId
        AND c.status = com.example.mealprep.ai.domain.entity.CallStatus.SUCCEEDED
        AND c.createdAt > :since
      GROUP BY c.taskType
      """)
  List<Object[]> sumCostMicroPenceForUserSinceByTaskType(
      @Param("userId") UUID userId, @Param("since") Instant since);

  /** Global last-24h spend across all users — admin observability. */
  @Query(
      """
      SELECT COALESCE(SUM(c.costMicroPence), 0)
      FROM AiCallLog c
      WHERE c.status = com.example.mealprep.ai.domain.entity.CallStatus.SUCCEEDED
        AND c.createdAt > :since
      """)
  long sumCostMicroPenceGlobalSince(@Param("since") Instant since);

  /**
   * SUCCEEDED rows for a user created after {@code since}, oldest first. Used to compute {@code
   * Retry-After} from the oldest counted row's {@code created_at + window}.
   */
  @Query(
      """
      SELECT c
      FROM AiCallLog c
      WHERE c.userId = :userId
        AND c.status = com.example.mealprep.ai.domain.entity.CallStatus.SUCCEEDED
        AND c.createdAt > :since
      ORDER BY c.createdAt ASC
      """)
  List<AiCallLog> findSucceededForUserSinceOrderByCreatedAtAsc(
      @Param("userId") UUID userId, @Param("since") Instant since);
}
