package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link InventoryItem}. Cross-module callers go through {@code
 * ProvisionQueryService} / {@code ProvisionUpdateService} — enforced by {@code
 * ProvisionsBoundaryTest} (ArchUnit). The interface is {@code public} only because the in-module
 * {@code domain.service.internal} package needs to inject it; package-private would prevent any
 * reference from another package, including same-module ones. The boundary test, not Java
 * visibility, fences cross-module reach-through.
 */
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

  /**
   * Page of items belonging to {@code userId} with {@code itemStatus} matching, optionally filtered
   * by {@code storageLocation} (null → no filter) and {@code isStaple} (null → no filter).
   */
  @Query(
      "SELECT i FROM InventoryItem i"
          + " WHERE i.userId = :userId"
          + " AND i.itemStatus = :itemStatus"
          + " AND (:storageLocation IS NULL OR i.storageLocation = :storageLocation)"
          + " AND (:isStaple IS NULL OR i.isStaple = :isStaple)")
  Page<InventoryItem> findActiveForUser(
      UUID userId,
      ItemLifecycleStatus itemStatus,
      StorageLocation storageLocation,
      Boolean isStaple,
      Pageable pageable);

  /** Look up by id, scoped to the owning user — used to enforce the 404-on-other-owner rule. */
  Optional<InventoryItem> findByIdAndUserId(UUID id, UUID userId);

  /**
   * Unpaged list of inventory items belonging to {@code userId} with the given lifecycle {@code
   * itemStatus}. Driven by 01f's planner-bundle aggregator — bounded by per-user state (typical
   * pantry has tens of rows; the 01f IT asserts no N+1 explosion).
   */
  List<InventoryItem> findAllByUserIdAndItemStatus(UUID userId, ItemLifecycleStatus itemStatus);

  /**
   * Unpaged list of staple inventory items belonging to {@code userId} whose {@code status} is in
   * the given set. Driven by 01f's planner-bundle aggregator — surfaces staples needing
   * replenishment ({@code LOW}/{@code OUT}). The {@code is_staple = true} predicate is enforced in
   * the method name.
   */
  List<InventoryItem> findAllByUserIdAndIsStapleTrueAndStatusIn(
      UUID userId, Collection<StapleStatus> statuses);

  /**
   * FIFO-by-expiry inventory rows for a user + mapping key, restricted to {@code ACTIVE}. Ordered
   * by {@code expiry_date ASC NULLS LAST} so oldest-expiring items are deducted first; rows with no
   * expiry date sink to the bottom. Driven by 01g's {@code InventoryDeductionEngine} (cook-event +
   * standalone-consumption flows).
   */
  @Query(
      "SELECT i FROM InventoryItem i"
          + " WHERE i.userId = :userId"
          + " AND i.ingredientMappingKey = :ingredientMappingKey"
          + " AND i.itemStatus = com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE"
          + " ORDER BY CASE WHEN i.expiryDate IS NULL THEN 1 ELSE 0 END, i.expiryDate ASC")
  List<InventoryItem> findActiveByMappingKeyOrderByExpiryAsc(
      UUID userId, String ingredientMappingKey);

  /**
   * Single {@code ACTIVE} row matching {@code (userId, ingredientMappingKey, storageLocation,
   * expiryDate)} — the expiry-aware merge predicate from LLD line 635-638 (grocery import).
   * Asymmetric null handling: when {@code expiryDate} is null, only rows with null expiry match;
   * when it's non-null, only rows with equal expiry match. Returns the first such row (the merge
   * predicate is unique-by-construction once the per-line iteration completes; multiple matches
   * would only arise mid-iteration if a prior import-time merge created a sibling, which the
   * single-line iteration already handled).
   */
  @Query(
      "SELECT i FROM InventoryItem i"
          + " WHERE i.userId = :userId"
          + " AND i.ingredientMappingKey = :ingredientMappingKey"
          + " AND i.storageLocation = :storageLocation"
          + " AND ((:expiryDate IS NULL AND i.expiryDate IS NULL)"
          + "      OR (:expiryDate IS NOT NULL AND i.expiryDate = :expiryDate))"
          + " AND i.itemStatus = com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus.ACTIVE")
  List<InventoryItem> findActiveForExpiryMerge(
      UUID userId,
      String ingredientMappingKey,
      StorageLocation storageLocation,
      java.time.LocalDate expiryDate);

  /**
   * Convenience wrapper around {@link #findActiveForExpiryMerge}; returns the first match (or
   * empty). 01h's import processor consumes this on the expiry-aware merge path.
   */
  default Optional<InventoryItem> findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
      UUID userId,
      String ingredientMappingKey,
      StorageLocation storageLocation,
      java.time.LocalDate expiryDate) {
    List<InventoryItem> matches =
        findActiveForExpiryMerge(userId, ingredientMappingKey, storageLocation, expiryDate);
    return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
  }
}
