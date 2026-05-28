package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GroceryOrder}. Package-private — cross-module callers go
 * through {@code GroceryOrderService}. Verbatim from lld/grocery.md lines 501-511.
 * {@code @EntityGraph} on the hot-read load keeps order + lines to a single JOIN (no N+1).
 */
interface GroceryOrderRepository extends JpaRepository<GroceryOrder, UUID> {

  @EntityGraph(attributePaths = {"lines"})
  Optional<GroceryOrder> findWithLinesById(UUID id);

  Page<GroceryOrder> findAllByUserIdAndStatusNotInOrderByCreatedAtDesc(
      UUID userId, Collection<GroceryOrderStatus> excludedStatuses, Pageable p);

  Optional<GroceryOrder> findByProviderKeyAndProviderOrderId(
      String providerKey, String providerOrderId);

  @Query(
      """
      select o from GroceryOrder o where o.shoppingListId = :listId
        and o.status not in ('CANCELLED', 'RECONCILED', 'ARCHIVED')""")
  List<GroceryOrder> findActiveByShoppingListId(@Param("listId") UUID listId);

  /**
   * Immediate bulk status+reason update bypassing the persistence context — used by {@code
   * OrderFailureRecorder} in its {@code REQUIRES_NEW} transaction so the failure-forward state
   * STICKS even when the main (locked) transaction rolls back. A bulk update is not subject to the
   * shared OSIV session's flush/rollback (the gotcha a managed-entity {@code save} would hit here).
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update GroceryOrder o set o.status = :status, o.statusReason = :reason, o.updatedAt = :now
        where o.id = :id""")
  int updateStatusAndReason(
      @Param("id") UUID id,
      @Param("status") GroceryOrderStatus status,
      @Param("reason") String reason,
      @Param("now") Instant now);

  /** Immediate provider-state failure-counter bump (same REQUIRES_NEW rationale as above). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      update GroceryProviderState s
        set s.consecutiveFailures = s.consecutiveFailures + 1,
            s.lastFailureAt = :now,
            s.lastFailureReason = :reason,
            s.updatedAt = :now
        where s.userId = :userId and s.providerKey = :providerKey""")
  int bumpProviderFailure(
      @Param("userId") UUID userId,
      @Param("providerKey") String providerKey,
      @Param("reason") String reason,
      @Param("now") Instant now);
}
