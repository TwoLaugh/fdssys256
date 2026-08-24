package com.example.mealprep.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * G11 feature flag pair for the graph-batch ingest path (G06) — bound to {@code
 * mealprep.graph.import.*}. Consumed ONLY by {@code GraphBatchIngestService}; no planner code reads
 * these properties (the pool stays flag-free by design).
 *
 * <p>{@code enabled} — the ingest gate: off by default until the design-doc §7 quality gate passes
 * (standing law 7). Flipping OFF does not remove already-imported dishes; withdrawal is the
 * documented archive-by-jobId procedure (G11).
 *
 * <p>{@code allowRestrictedDietFlags} — the residual Nadia-gate policy: while {@code false}, any
 * batch containing a dish that certifies {@code vegan} or {@code gluten_free} is rejected whole
 * (generated dishes must not enter restricted-diet pools until the Nadia coverage gate passes).
 */
@ConfigurationProperties(prefix = "mealprep.graph.import")
public record GraphImportProperties(boolean enabled, boolean allowRestrictedDietFlags) {}
