package com.example.mealprep.discovery.api.dto;

import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Cross-module read shape for {@code DiscoveryScrapeLog}. Mirrors LLD lines 227-236. */
public record DiscoveryScrapeLogEntryDto(
    UUID id,
    UUID jobId,
    String sourceKey,
    String candidateUrl,
    String canonicalUrl,
    ScrapeOutcome status,
    Integer httpStatusCode,
    RobotsTxtOutcome robotsTxtOutcome,
    Integer latencyMs,
    String contentFingerprint,
    String extractionMethod,
    BigDecimal extractionConfidence,
    UUID recipeId,
    ScrapeSkipReason skipReason,
    String errorClass,
    String errorMessage,
    Instant occurredAt) {}
