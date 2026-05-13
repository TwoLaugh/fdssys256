package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PendingChange}. {@code public} so the in-module {@code
 * domain.service} package can inject it; cross-module isolation comes from the (01f-shipping)
 * {@code ModuleBoundaryArchTest}. Verbatim from {@code lld/adaptation-pipeline.md} lines 432-445.
 */
public interface PendingChangeRepository extends JpaRepository<PendingChange, UUID> {

  Optional<PendingChange> findByRecipeIdAndChangeDimensionAndStatus(
      UUID rid, ChangeDimension d, PendingChangeStatus s);

  List<PendingChange> findAllByUserIdAndStatus(UUID userId, PendingChangeStatus status, Sort sort);

  Page<PendingChange> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable pageable);

  @Query(
      """
      select pc from PendingChange pc
       where pc.status = com.example.mealprep.adaptation.domain.enums.PendingChangeStatus.PENDING
         and pc.expiresAt < :now""")
  List<PendingChange> findExpiredPending(@Param("now") Instant now, Pageable pageable);

  /** Per-week budget query: top-N PENDING by impact x confidence x age. */
  @Query(
      """
      select pc from PendingChange pc
       where pc.userId = :uid
         and pc.status = com.example.mealprep.adaptation.domain.enums.PendingChangeStatus.PENDING
       order by pc.impactScore desc, pc.confidence desc, pc.createdAt asc""")
  List<PendingChange> findRankedPending(@Param("uid") UUID userId, Pageable pageable);
}
