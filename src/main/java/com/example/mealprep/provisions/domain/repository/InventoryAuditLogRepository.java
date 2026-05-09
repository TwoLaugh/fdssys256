package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link InventoryAuditLog}. Append-only — only insertion and paginated
 * read by inventory item id are exposed. The GET endpoint over the audit log lands in 01b.
 */
public interface InventoryAuditLogRepository extends JpaRepository<InventoryAuditLog, UUID> {

  Page<InventoryAuditLog> findByInventoryItemIdOrderByOccurredAtDesc(
      UUID inventoryItemId, Pageable pageable);
}
