package com.example.mealprep.discovery.api.dto;

/**
 * Response shape for {@code POST /api/v1/discovery/admin/run-orphan-sweep}. In 01b always carries
 * {@code resumedCount = 0}; discovery-01d wires the real implementation.
 */
public record OrphanSweepResultDto(int resumedCount) {}
