package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.LifestyleConfigAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LifestyleConfigAuditLog}. Append-only — exposes only insertion
 * and paginated reads. The {@code findByLifestyleConfigIdAndFieldPathStartingWith...} variant
 * powers the section filter on the audit-log endpoint (e.g. {@code ?section=batchCooking} returns
 * rows where {@code field_path = 'batchCooking'} or starts with {@code 'batchCooking.'}).
 */
public interface LifestyleConfigAuditLogRepository
    extends JpaRepository<LifestyleConfigAuditLog, UUID> {

  Page<LifestyleConfigAuditLog> findByLifestyleConfigIdOrderByOccurredAtDesc(
      UUID lifestyleConfigId, Pageable pageable);

  Page<LifestyleConfigAuditLog>
      findByLifestyleConfigIdAndFieldPathStartingWithOrderByOccurredAtDesc(
          UUID lifestyleConfigId, String fieldPathPrefix, Pageable pageable);
}
