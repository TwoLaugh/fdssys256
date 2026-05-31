package com.example.mealprep.nutrition.domain.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Cross-module read-side SPI exposing nutrition-module operational signals for the system status
 * endpoint ({@code GET /api/v1/admin/status}, capability C-G-032). Kept separate from the broad
 * {@link NutritionQueryService} so the ops aggregator depends only on this narrow surface.
 */
public interface NutritionOperationalStatusService {

  /**
   * Timestamp of the most recent outbound USDA FoodData Central call this process has made, or
   * empty if none since startup. This is an in-memory, single-instance signal (not persisted) — it
   * answers "is the USDA integration live and when did we last touch it?" for ops, and resets on
   * restart.
   */
  Optional<Instant> lastUsdaCallAt();
}
