package com.example.mealprep.nutrition.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Result of a G05 seed run. {@code status} is {@code "OK"} when no collision occurred, {@code
 * "FAILED"} otherwise (HTTP 409 at the controller) — a failed seed is a HARD STOP for the runbook
 * and the G06 pre-flight: a collision means lazy population (or a user correction) reached a
 * spike-canon key first, exactly the poisoning the seed exists to prevent, and a human must
 * adjudicate. Because {@code search_term} is {@code updatable = false}, adjudication means delete +
 * re-seed under human review — collided rows are NEVER overwritten by this endpoint.
 */
public record IngredientMappingSeedReport(
    int inserted,
    int skippedIdentical,
    List<RejectedSeedRow> rejected,
    List<SeedCollision> collisions,
    String status,
    JsonNode meta) {

  public static final String STATUS_OK = "OK";
  public static final String STATUS_FAILED = "FAILED";

  /** Row refused before any write (e.g. searchTerm not in engine normal form). */
  public record RejectedSeedRow(String searchTerm, String reason) {}

  /**
   * An existing row differs from the seed row on (source, externalId, nutritionPer100g). The
   * existing row is untouched; {@code firstDivergingField} names the first field that differs.
   */
  public record SeedCollision(
      String searchTerm,
      IngredientMappingSource existingSource,
      String existingExternalId,
      String firstDivergingField,
      String note) {}
}
