package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.TasteProfileAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link TasteProfileAuditLog}. Append-only — only insertion and
 * paginated read by taste-profile id are exposed.
 */
public interface TasteProfileAuditLogRepository extends JpaRepository<TasteProfileAuditLog, UUID> {

  Page<TasteProfileAuditLog> findByTasteProfileIdOrderByOccurredAtDesc(
      UUID tasteProfileId, Pageable pageable);
}
