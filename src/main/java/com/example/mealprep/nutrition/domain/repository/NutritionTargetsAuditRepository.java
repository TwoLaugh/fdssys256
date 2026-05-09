package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.NutritionTargetsAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link NutritionTargetsAuditLog}. Append-only — only insertion and
 * paginated read by aggregate id are exposed. {@code public} for the same reason as {@code
 * NutritionTargetsRepository}; cross-module reach-through is fenced by {@code
 * NutritionBoundaryTest}.
 */
public interface NutritionTargetsAuditRepository
    extends JpaRepository<NutritionTargetsAuditLog, UUID> {

  Page<NutritionTargetsAuditLog> findByTargetsIdOrderByOccurredAtDesc(
      UUID targetsId, Pageable pageable);
}
