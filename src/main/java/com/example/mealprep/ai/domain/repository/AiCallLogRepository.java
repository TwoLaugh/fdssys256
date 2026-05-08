package com.example.mealprep.ai.domain.repository;

import com.example.mealprep.ai.domain.entity.AiCallLog;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AiCallLog}. Package-private at the package level — cross-module
 * callers go through {@code AiService} (which writes) and the future {@code AiCostTrackingService}
 * (which reads aggregates).
 */
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {}
