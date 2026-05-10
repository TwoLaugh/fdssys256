package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.IntakeAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link IntakeAuditLog}. Append-only — only insertion and paginated
 * read by intake-day id are exposed.
 */
public interface IntakeAuditRepository extends JpaRepository<IntakeAuditLog, UUID> {

  Page<IntakeAuditLog> findByIntakeDayIdOrderByOccurredAtDesc(UUID intakeDayId, Pageable pageable);
}
