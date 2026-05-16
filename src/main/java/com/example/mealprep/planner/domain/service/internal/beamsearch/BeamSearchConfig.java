package com.example.mealprep.planner.domain.service.internal.beamsearch;

/**
 * Configuration for one beam-search invocation. Defaults sourced from {@code PlannerProperties}
 * (beamWidth=20, topN=5, maxPoolPerSlot=50). The composer (01j) builds an instance per search run —
 * that lets re-opt narrow the beam without mutating global config.
 *
 * <p>Invariants enforced in the compact constructor: {@code width >= 1}, {@code topN >= 1}, {@code
 * maxPoolPerSlot >= 1}, and {@code width >= topN} (you can't return more results than the beam
 * holds).
 */
public record BeamSearchConfig(int width, int topN, int maxPoolPerSlot) {

  public BeamSearchConfig {
    if (width < 1) {
      throw new IllegalArgumentException("width must be >= 1, got " + width);
    }
    if (topN < 1) {
      throw new IllegalArgumentException("topN must be >= 1, got " + topN);
    }
    if (maxPoolPerSlot < 1) {
      throw new IllegalArgumentException("maxPoolPerSlot must be >= 1, got " + maxPoolPerSlot);
    }
    if (width < topN) {
      throw new IllegalArgumentException("width (" + width + ") must be >= topN (" + topN + ")");
    }
  }
}
