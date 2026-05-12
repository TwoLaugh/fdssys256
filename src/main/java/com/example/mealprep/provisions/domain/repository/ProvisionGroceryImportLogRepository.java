package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.ProvisionGroceryImportLog;
import com.example.mealprep.provisions.domain.entity.ProvisionGroceryImportLog.GroceryImportLogId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link ProvisionGroceryImportLog}. {@code public} so the in-module
 * {@code domain.service.internal} package can inject it; {@code ProvisionsBoundaryTest} fences
 * cross-module reach-through.
 *
 * <p>The {@link #existsByIdUserIdAndIdSourceAndIdSourceRef(UUID, ItemSource, String)} helper is the
 * idempotency check at the entry of the grocery-import flow (LLD line 632).
 */
public interface ProvisionGroceryImportLogRepository
    extends JpaRepository<ProvisionGroceryImportLog, GroceryImportLogId> {

  /** Idempotency check on the composite PK. */
  boolean existsByIdUserIdAndIdSourceAndIdSourceRef(
      UUID userId, ItemSource source, String sourceRef);
}
