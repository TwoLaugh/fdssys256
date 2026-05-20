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

  // Underscore-separated property path so Spring Data unambiguously traverses the IntakeDay
  // association (the entity has no scalar intakeDayId field — only @ManyToOne IntakeDay). Without
  // the underscore the auto-derived count query for the Page return fails at construction with
  // InvalidDataAccessApiUsageException and the endpoint 500s.
  Page<IntakeAuditLog> findByIntakeDay_IdOrderByOccurredAtDesc(UUID intakeDayId, Pageable pageable);
}
