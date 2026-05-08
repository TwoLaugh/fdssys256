package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.spi.TaskType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Admin-side read facade over {@code ai_call_log}. Exposed only to {@code AdminAiController} —
 * cross-module callers go through {@code AiService} for writes and direct aggregate-specific
 * services for reads.
 */
public interface AdminAiQueryService {

  CostSummaryDto getCostSummary(int windowHours);

  Page<AiCallLogDto> getCallLog(TaskType taskType, UUID userId, Pageable pageable);
}
