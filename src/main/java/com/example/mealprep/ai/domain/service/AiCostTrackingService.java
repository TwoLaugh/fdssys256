package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.spi.TaskType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side aggregate API over {@code ai_call_log}. Used by admin observability (the actual REST
 * endpoint lands in 01d) and — internally — by {@code CostBudgetGuard} for its pre-call evaluation.
 *
 * <p>All amounts are in pence (not micropence) for human-friendly downstream rendering — the
 * precision of micropence is preserved internally by the repository layer, but every value crossing
 * this interface is already converted.
 */
public interface AiCostTrackingService {

  /**
   * Total pence spent by {@code userId} on {@link com.example.mealprep.ai.domain.entity.CallStatus}
   * SUCCEEDED rows in the rolling {@code window} ending at "now". {@code window} is consumed
   * verbatim — callers wanting "last 24h" pass {@link Duration#ofHours(long)}.
   */
  BigDecimal pencesSpentBy(UUID userId, Duration window);

  /** Per-{@link TaskType} breakdown for the same window. Empty map when no SUCCEEDED rows match. */
  Map<TaskType, BigDecimal> pencesSpentByUserPerTaskType(UUID userId, Duration window);

  /** Global last-24h spend across all users. Admin observability. */
  BigDecimal pencesSpentGlobalLast24h();
}
