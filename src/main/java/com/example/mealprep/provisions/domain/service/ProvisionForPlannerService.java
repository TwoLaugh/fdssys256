package com.example.mealprep.provisions.domain.service;

import com.example.mealprep.provisions.api.dto.ProvisionForPlannerBundleDto;
import java.util.UUID;

/**
 * Cross-module facade for the planner's provisions-utilisation sub-score. Single read entry-point —
 * collapses the 4+ separate fetches (inventory, equipment, budget, supplier-products) into one
 * call.
 *
 * <p>Read-only; pure aggregation. The multi-user {@code getBundlesByUserIds} variant from the LLD
 * is deferred — no consumer currently exists for it. See ticket {@code provisions-01f}.
 */
public interface ProvisionForPlannerService {

  /**
   * Return the planner-friendly snapshot for {@code userId}. Empty user state produces an
   * empty-but-valid bundle: empty lists, {@code null} budget, empty supplier-price map, {@code
   * staleness.supplierCacheCoverageBps = 0}, {@code staleness.inRampUpWindow = false}. Never
   * throws.
   */
  ProvisionForPlannerBundleDto getBundle(UUID userId);
}
