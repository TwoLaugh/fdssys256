package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HardConstraintsAuditLog}. Append-only — only insertion and
 * paginated read by aggregate id are exposed.
 */
public interface HardConstraintsAuditLogRepository
    extends JpaRepository<HardConstraintsAuditLog, UUID> {

  Page<HardConstraintsAuditLog> findByHardConstraintsIdOrderByOccurredAtDesc(
      UUID hardConstraintsId, Pageable pageable);
}
