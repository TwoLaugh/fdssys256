package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiCostTrackingService;
import com.example.mealprep.ai.spi.TaskType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed implementation of {@link AiCostTrackingService}. Pure read-side; every method is
 * marked {@code @Transactional(readOnly = true)} per the project's repository discipline. Pence
 * values returned to callers are micropence ÷ 1_000_000 with HALF_UP rounding to two decimal places
 * — finer-grained values land in the underlying ledger, but the API surface deals in human-readable
 * pence for admin-dashboard rendering.
 */
@Service
@Transactional(readOnly = true)
public class AiCostTrackingServiceImpl implements AiCostTrackingService {

  static final BigDecimal MICRO_PER_PENCE = BigDecimal.valueOf(1_000_000L);

  private final AiCallLogRepository repository;
  private final Clock clock;

  public AiCostTrackingServiceImpl(AiCallLogRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public BigDecimal pencesSpentBy(UUID userId, Duration window) {
    if (userId == null || window == null) {
      return BigDecimal.ZERO;
    }
    Instant since = Instant.now(clock).minus(window);
    long microPence = repository.sumCostMicroPenceForUserSince(userId, since);
    return microPenceToPence(microPence);
  }

  @Override
  public Map<TaskType, BigDecimal> pencesSpentByUserPerTaskType(UUID userId, Duration window) {
    Map<TaskType, BigDecimal> out = new EnumMap<>(TaskType.class);
    if (userId == null || window == null) {
      return out;
    }
    Instant since = Instant.now(clock).minus(window);
    for (Object[] row : repository.sumCostMicroPenceForUserSinceByTaskType(userId, since)) {
      TaskType type = (TaskType) row[0];
      long microPence = ((Number) row[1]).longValue();
      out.put(type, microPenceToPence(microPence));
    }
    return out;
  }

  @Override
  public BigDecimal pencesSpentGlobalLast24h() {
    Instant since = Instant.now(clock).minus(Duration.ofHours(24));
    return microPenceToPence(repository.sumCostMicroPenceGlobalSince(since));
  }

  static BigDecimal microPenceToPence(long microPence) {
    return BigDecimal.valueOf(microPence).divide(MICRO_PER_PENCE, 2, RoundingMode.HALF_UP);
  }
}
