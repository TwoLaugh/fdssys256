package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AiOperationalStatusService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link AiOperationalStatusService}. Pure read-side. "Month-to-date" is anchored to the
 * first instant of the current calendar month in UTC (consistent with the rest of the AI module's
 * UTC clock usage), so the figure resets at each month boundary rather than being a rolling 30-day
 * window.
 */
@Service
@Transactional(readOnly = true)
public class AiOperationalStatusServiceImpl implements AiOperationalStatusService {

  private final AiCallLogRepository repository;
  private final Clock clock;

  public AiOperationalStatusServiceImpl(AiCallLogRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  public Optional<Instant> lastAiCallAt() {
    return Optional.ofNullable(repository.findMaxCreatedAt());
  }

  @Override
  public BigDecimal monthToDatePence() {
    long microPence = repository.sumCostMicroPenceGlobalSince(startOfCurrentMonthUtc());
    return AiCostTrackingServiceImpl.microPenceToPence(microPence);
  }

  private Instant startOfCurrentMonthUtc() {
    ZonedDateTime now = Instant.now(clock).atZone(ZoneOffset.UTC);
    return now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
