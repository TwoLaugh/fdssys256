package com.example.mealprep.discovery.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /api/v1/discovery/admin/graph-batches/ingest}: a server-local absolute path
 * to a G01-exported batch directory ({@code export/batch-<date>-<seq>/}). Single-host deployment —
 * a path is the simplest correct transport for a directory artifact (D4 option (a), v1). The
 * service requires the path to be absolute and to contain {@code manifest.json}; it is admin-gated
 * single-operator tooling, deliberately not hardened further.
 */
public record GraphBatchIngestRequest(@NotBlank String batchPath) {}
