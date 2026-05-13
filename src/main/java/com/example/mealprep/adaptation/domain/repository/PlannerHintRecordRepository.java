package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PlannerHintRecord}. {@code public} so the in-module {@code
 * domain.service} package can inject it; cross-module isolation comes from the (01f-shipping)
 * {@code ModuleBoundaryArchTest}. Verbatim from {@code lld/adaptation-pipeline.md} lines 460-468.
 */
public interface PlannerHintRecordRepository extends JpaRepository<PlannerHintRecord, UUID> {

  @Query("select h from PlannerHintRecord h where h.versionId = :vid and h.invalidatedAt is null")
  List<PlannerHintRecord> findActiveForVersion(@Param("vid") UUID versionId);

  /**
   * Bulk-invalidate the active hints on the prior version when a new version supersedes it. Returns
   * the row count touched; callers in 01f log a single line per emit.
   */
  @Modifying
  @Query(
      "update PlannerHintRecord h set h.invalidatedAt = :now "
          + "where h.versionId = :oldVersionId and h.invalidatedAt is null")
  int invalidateForOldVersion(@Param("oldVersionId") UUID oldVersionId, @Param("now") Instant now);
}
