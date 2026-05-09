package com.example.mealprep.nutrition.domain.service;

import com.example.mealprep.nutrition.api.dto.NutritionTargetsAuditEntryDto;
import com.example.mealprep.nutrition.api.dto.TargetsDto;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read API for the nutrition module's targets aggregate. Cross-module callers (planner, floor-gate,
 * intake aggregation in later sub-tickets) consume this to retrieve a user's targets — partial in
 * 01a (targets only).
 */
public interface NutritionQueryService {

  /**
   * Read-by-others contract: return the calling user's nutrition targets, or empty if no row exists
   * yet. The returned DTO includes all four child collections (per-meal distribution,
   * micro-targets, eating window, activity adjustments) eagerly populated.
   */
  Optional<TargetsDto> getTargets(UUID userId);

  /** Paginated audit log of changes to the user's targets, newest-first. */
  Page<NutritionTargetsAuditEntryDto> getTargetsAuditLog(UUID userId, Pageable pageable);
}
