package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.api.dto.ReasonAggregateRow;
import com.example.mealprep.provisions.api.dto.TopWastedItemDto;
import com.example.mealprep.provisions.domain.entity.WasteEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link WasteEntry}. Cross-module callers go through {@code
 * ProvisionQueryService} / {@code ProvisionUpdateService} — enforced by {@code
 * ProvisionsBoundaryTest} (ArchUnit). {@code public} for the same reason as the sibling repos — the
 * in-module {@code domain.service.internal} package needs to inject it; the boundary test, not Java
 * visibility, fences cross-module reach-through.
 */
public interface WasteEntryRepository extends JpaRepository<WasteEntry, UUID> {

  Page<WasteEntry> findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDesc(
      UUID userId, LocalDate from, LocalDate to, Pageable p);

  List<WasteEntry> findAllByUserIdAndOccurredOnBetween(
      UUID userId, LocalDate from, LocalDate to, Pageable p);

  @Query(
      "select new com.example.mealprep.provisions.api.dto.ReasonAggregateRow("
          + " w.reason, count(w), coalesce(sum(w.costEstimate), 0))"
          + " from WasteEntry w"
          + " where w.userId = :userId and w.occurredOn between :from and :to"
          + " group by w.reason")
  List<ReasonAggregateRow> aggregateByReason(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  @Query(
      "select new com.example.mealprep.provisions.api.dto.TopWastedItemDto("
          + " w.itemName, count(w), coalesce(sum(w.costEstimate), 0))"
          + " from WasteEntry w"
          + " where w.userId = :userId and w.occurredOn between :from and :to"
          + " group by w.itemName"
          + " order by count(w) desc, coalesce(sum(w.costEstimate), 0) desc")
  List<TopWastedItemDto> findTopWastedItems(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      Pageable p);
}
