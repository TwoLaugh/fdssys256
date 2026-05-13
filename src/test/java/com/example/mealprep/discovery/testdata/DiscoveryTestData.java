package com.example.mealprep.discovery.testdata;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceType;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test data builders for the discovery module. Centralised so the migration IT, future flow ITs,
 * and unit tests share the same default shape.
 */
public final class DiscoveryTestData {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private DiscoveryTestData() {}

  public static DiscoverySource sampleSource(String sourceKey) {
    return DiscoverySource.builder()
        .id(UUID.randomUUID())
        .sourceKey(sourceKey)
        .displayName("Sample Source " + sourceKey)
        .sourceType(DiscoverySourceType.CURATED)
        .kind(DiscoverySourceKind.SITEMAP)
        .baseUrl("https://example.test/")
        .enabled(true)
        .userDisabled(false)
        .requestsPerMinute(6)
        .requestsPerDay(500)
        .respectRobotsTxt(true)
        .userAgent("MealPrepAI/1.0 (+https://example.test)")
        .crawlConfig(OBJECT_MAPPER.createObjectNode().put("sitemap_url", "https://example.test/sitemap.xml"))
        .failureStreak(0)
        .qualityScore(new BigDecimal("0.750"))
        .build();
  }

  public static DiscoveryConstraints sampleConstraints() {
    return new DiscoveryConstraints(
        1,
        List.of("East Asian"),
        List.of("dinner"),
        45,
        List.of("peanuts"),
        List.of("vegetarian"),
        List.of("lighter dishes"),
        20);
  }

  public static DiscoveryJob sampleJob(UUID userId) {
    return DiscoveryJob.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .trigger(DiscoveryJobTrigger.COLD_START)
        .requestedCount(10)
        .constraintsJson(OBJECT_MAPPER.valueToTree(sampleConstraints()))
        .sourcesRequested(new ArrayList<>(List.of("src_a", "src_b")))
        .status(DiscoveryJobStatus.QUEUED)
        .queuedAt(Instant.now())
        .candidatesSeen(0)
        .candidatesAfterFilter(0)
        .recipesIngested(0)
        .recipesSkippedDuplicate(0)
        .sourcesSucceeded(new ArrayList<>())
        .sourcesFailed(new ArrayList<>())
        .traceId(UUID.randomUUID())
        .build();
  }

  public static DiscoveryScrapeLog sampleScrapeLog(UUID jobId) {
    return DiscoveryScrapeLog.builder()
        .id(UUID.randomUUID())
        .jobId(jobId)
        .sourceKey("src_a")
        .candidateUrl("https://example.test/recipe/1")
        .status(ScrapeOutcome.SUCCESS)
        .robotsTxtOutcome(RobotsTxtOutcome.ALLOWED)
        .latencyMs(123)
        .occurredAt(Instant.now())
        .build();
  }
}
