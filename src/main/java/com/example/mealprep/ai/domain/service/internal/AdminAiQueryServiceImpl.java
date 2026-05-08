package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.api.mapper.AiCallLogMapper;
import com.example.mealprep.ai.domain.repository.AiCallLogRepository;
import com.example.mealprep.ai.domain.service.AdminAiQueryService;
import com.example.mealprep.ai.spi.TaskType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only aggregations over {@code ai_call_log}. Pure JPA — no external calls. */
@Service
public class AdminAiQueryServiceImpl implements AdminAiQueryService {

  static final int TOP_USERS_LIMIT = 20;
  static final int MAX_WINDOW_HOURS = 24 * 30; // 30 days

  private final AiCallLogRepository repository;
  private final AiCallLogMapper mapper;
  private final Clock clock;

  public AdminAiQueryServiceImpl(
      AiCallLogRepository repository, AiCallLogMapper mapper, Clock clock) {
    this.repository = repository;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public CostSummaryDto getCostSummary(int windowHours) {
    int clampedHours = Math.max(1, Math.min(MAX_WINDOW_HOURS, windowHours));
    Instant since = Instant.now(clock).minus(Duration.ofHours(clampedHours));
    long totalCalls = repository.countSince(since);
    long totalCost = repository.sumCostSince(since);
    List<Object[]> rows =
        repository.findTopUsersByCostSince(since, PageRequest.of(0, TOP_USERS_LIMIT));
    List<CostSummaryDto.UserCostEntry> top = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      UUID userId = (UUID) row[0];
      long count = ((Number) row[1]).longValue();
      long cost = ((Number) row[2]).longValue();
      top.add(new CostSummaryDto.UserCostEntry(userId, count, cost));
    }
    return new CostSummaryDto(clampedHours, totalCalls, totalCost, List.copyOf(top));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AiCallLogDto> getCallLog(TaskType taskType, UUID userId, Pageable pageable) {
    Page<com.example.mealprep.ai.domain.entity.AiCallLog> page;
    if (taskType != null && userId != null) {
      page = repository.findByTaskTypeAndUserIdOrderByCreatedAtDesc(taskType, userId, pageable);
    } else if (taskType != null) {
      page = repository.findByTaskTypeOrderByCreatedAtDesc(taskType, pageable);
    } else if (userId != null) {
      page = repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    } else {
      page = repository.findAllByOrderByCreatedAtDesc(pageable);
    }
    return page.map(mapper::toDto);
  }
}
