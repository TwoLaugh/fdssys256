package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.mapper.DiscoveryJobMapper;
import com.example.mealprep.discovery.api.mapper.DiscoveryScrapeLogMapper;
import com.example.mealprep.discovery.api.mapper.DiscoverySourceMapper;
import com.example.mealprep.discovery.config.HttpFetchException;
import com.example.mealprep.discovery.domain.entity.DiscoveryGoogleCseUsage;
import com.example.mealprep.discovery.domain.entity.DiscoveryJob;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoveryScrapeLog;
import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceType;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeOutcome;
import com.example.mealprep.discovery.domain.entity.ScrapeSkipReason;
import com.example.mealprep.discovery.exception.DiscoveryAllSourcesUnavailableException;
import com.example.mealprep.discovery.exception.DiscoveryConstraintInvalidException;
import com.example.mealprep.discovery.exception.DiscoveryJobAlreadyTerminalException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoveryJobTimeoutException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import com.example.mealprep.discovery.exception.DiscoverySourceUnavailableException;
import com.example.mealprep.discovery.exception.ExtractionFailedException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of the discovery hand-written mappers (JobMapper, ScrapeLogMapper,
 * SourceMapper) plus Lombok entity getters, exception accessors, and constructor invariants. The
 * MapStruct {@code *MapperImpl} classes are excluded from Pitest, but the interface default-method
 * bodies AND the hand-written JobMapper are NOT — these tests pin every field copy + null guard.
 */
class DiscoveryMappersAndEntitiesTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  // =================== DiscoveryJobMapper ===================

  @Test
  void jobMapper_nullEntity_returnsNull() {
    DiscoveryJobMapper m = new DiscoveryJobMapper(objectMapper);
    assertThat(m.toDto(null)).isNull();
  }

  @Test
  void jobMapper_fullEntity_copiesAllFields() {
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant queuedAt = Instant.parse("2026-05-13T12:00:00Z");
    DiscoveryJob job =
        DiscoveryJob.builder()
            .id(jobId)
            .userId(userId)
            .trigger(DiscoveryJobTrigger.COLD_START)
            .requestedCount(7)
            .constraintsJson(objectMapper.valueToTree(DiscoveryTestData.sampleConstraints()))
            .sourcesRequested(new ArrayList<>(List.of("src_a", "src_b")))
            .status(DiscoveryJobStatus.RUNNING)
            .queuedAt(queuedAt)
            .startedAt(queuedAt.plusSeconds(5))
            .completedAt(null)
            .candidatesSeen(11)
            .candidatesAfterFilter(9)
            .recipesIngested(3)
            .recipesSkippedDuplicate(1)
            .sourcesSucceeded(new ArrayList<>(List.of("src_a")))
            .sourcesFailed(new ArrayList<>(List.of("src_b")))
            .errorSummary("partial")
            .traceId(traceId)
            .optimisticVersion(2L)
            .build();

    DiscoveryJobDto dto = new DiscoveryJobMapper(objectMapper).toDto(job);

    assertThat(dto.id()).isEqualTo(jobId);
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.trigger()).isEqualTo(DiscoveryJobTrigger.COLD_START);
    assertThat(dto.requestedCount()).isEqualTo(7);
    assertThat(dto.constraints()).isNotNull();
    assertThat(dto.constraints().schemaVersion()).isEqualTo(1);
    assertThat(dto.sourcesRequested()).containsExactly("src_a", "src_b");
    assertThat(dto.status()).isEqualTo(DiscoveryJobStatus.RUNNING);
    assertThat(dto.queuedAt()).isEqualTo(queuedAt);
    assertThat(dto.startedAt()).isEqualTo(queuedAt.plusSeconds(5));
    assertThat(dto.candidatesSeen()).isEqualTo(11);
    assertThat(dto.candidatesAfterFilter()).isEqualTo(9);
    assertThat(dto.recipesIngested()).isEqualTo(3);
    assertThat(dto.recipesSkippedDuplicate()).isEqualTo(1);
    assertThat(dto.sourcesSucceeded()).containsExactly("src_a");
    assertThat(dto.sourcesFailed()).containsExactly("src_b");
    assertThat(dto.errorSummary()).isEqualTo("partial");
    assertThat(dto.traceId()).isEqualTo(traceId);
    assertThat(dto.optimisticVersion()).isEqualTo(2L);
  }

  @Test
  void jobMapper_nullConstraintsJson_yieldsNullConstraintsField() {
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setConstraintsJson(null);
    DiscoveryJobDto dto = new DiscoveryJobMapper(objectMapper).toDto(job);
    assertThat(dto.constraints()).isNull();
  }

  @Test
  void jobMapper_nullNodeConstraintsJson_yieldsNullConstraintsField() {
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setConstraintsJson(NullNode.getInstance());
    DiscoveryJobDto dto = new DiscoveryJobMapper(objectMapper).toDto(job);
    assertThat(dto.constraints()).isNull();
  }

  @Test
  void jobMapper_invalidConstraintsJson_throwsIllegalState() {
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setConstraintsJson(objectMapper.createObjectNode().put("schemaVersion", "not-an-int"));
    assertThatThrownBy(() -> new DiscoveryJobMapper(objectMapper).toDto(job))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void jobMapper_nullSourceLists_copyToEmpty() {
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setSourcesRequested(null);
    job.setSourcesSucceeded(null);
    job.setSourcesFailed(null);
    DiscoveryJobDto dto = new DiscoveryJobMapper(objectMapper).toDto(job);
    assertThat(dto.sourcesRequested()).isEmpty();
    assertThat(dto.sourcesSucceeded()).isEmpty();
    assertThat(dto.sourcesFailed()).isEmpty();
  }

  @Test
  void jobMapper_toDtos_nullAndEmpty_returnEmpty() {
    DiscoveryJobMapper m = new DiscoveryJobMapper(objectMapper);
    assertThat(m.toDtos(null)).isEmpty();
    assertThat(m.toDtos(Collections.emptyList())).isEmpty();
  }

  @Test
  void jobMapper_toDtos_nonEmpty_returnsListInOrder() {
    DiscoveryJob a = DiscoveryTestData.sampleJob(UUID.randomUUID());
    DiscoveryJob b = DiscoveryTestData.sampleJob(UUID.randomUUID());
    DiscoveryJobMapper m = new DiscoveryJobMapper(objectMapper);
    assertThat(m.toDtos(List.of(a, b))).hasSize(2);
  }

  // =================== DiscoveryScrapeLogMapper (interface default) ===================

  @Test
  void scrapeLogMapper_nullEntity_returnsNull() {
    DiscoveryScrapeLogMapper m = new DiscoveryScrapeLogMapper() {};
    assertThat(m.toDto(null)).isNull();
  }

  @Test
  void scrapeLogMapper_fullEntity_copiesAllFields() {
    UUID id = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    Instant occurred = Instant.parse("2026-05-13T12:34:56Z");
    DiscoveryScrapeLog log =
        DiscoveryScrapeLog.builder()
            .id(id)
            .jobId(jobId)
            .sourceKey("src_a")
            .candidateUrl("https://x.test/c")
            .canonicalUrl("https://x.test/canon")
            .status(ScrapeOutcome.SUCCESS)
            .httpStatusCode(200)
            .robotsTxtOutcome(RobotsTxtOutcome.ALLOWED)
            .latencyMs(123)
            .contentFingerprint("fp")
            .extractionMethod("json_ld")
            .extractionConfidence(new BigDecimal("0.85"))
            .recipeId(recipeId)
            .skipReason(ScrapeSkipReason.DUPLICATE)
            .errorClass("E")
            .errorMessage("msg")
            .occurredAt(occurred)
            .build();

    DiscoveryScrapeLogMapper m = new DiscoveryScrapeLogMapper() {};
    DiscoveryScrapeLogEntryDto dto = m.toDto(log);

    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.jobId()).isEqualTo(jobId);
    assertThat(dto.sourceKey()).isEqualTo("src_a");
    assertThat(dto.candidateUrl()).isEqualTo("https://x.test/c");
    assertThat(dto.canonicalUrl()).isEqualTo("https://x.test/canon");
    assertThat(dto.status()).isEqualTo(ScrapeOutcome.SUCCESS);
    assertThat(dto.httpStatusCode()).isEqualTo(200);
    assertThat(dto.robotsTxtOutcome()).isEqualTo(RobotsTxtOutcome.ALLOWED);
    assertThat(dto.latencyMs()).isEqualTo(123);
    assertThat(dto.contentFingerprint()).isEqualTo("fp");
    assertThat(dto.extractionMethod()).isEqualTo("json_ld");
    assertThat(dto.extractionConfidence()).isEqualByComparingTo("0.85");
    assertThat(dto.recipeId()).isEqualTo(recipeId);
    assertThat(dto.skipReason()).isEqualTo(ScrapeSkipReason.DUPLICATE);
    assertThat(dto.errorClass()).isEqualTo("E");
    assertThat(dto.errorMessage()).isEqualTo("msg");
    assertThat(dto.occurredAt()).isEqualTo(occurred);
  }

  @Test
  void scrapeLogMapper_toDtos_nullAndEmpty_returnEmpty() {
    DiscoveryScrapeLogMapper m = new DiscoveryScrapeLogMapper() {};
    assertThat(m.toDtos(null)).isEmpty();
    assertThat(m.toDtos(Collections.emptyList())).isEmpty();
  }

  @Test
  void scrapeLogMapper_toDtos_nonEmpty_mapsEach() {
    DiscoveryScrapeLog a = DiscoveryTestData.sampleScrapeLog(UUID.randomUUID());
    DiscoveryScrapeLog b = DiscoveryTestData.sampleScrapeLog(UUID.randomUUID());
    DiscoveryScrapeLogMapper m = new DiscoveryScrapeLogMapper() {};
    assertThat(m.toDtos(List.of(a, b))).hasSize(2);
  }

  // =================== DiscoverySourceMapper (interface default) ===================

  @Test
  void sourceMapper_nullEntity_returnsNull() {
    DiscoverySourceMapper m = new DiscoverySourceMapper() {};
    assertThat(m.toDto(null)).isNull();
  }

  @Test
  void sourceMapper_fullEntity_copiesAllFields() {
    UUID id = UUID.randomUUID();
    Instant lastF = Instant.parse("2026-05-13T11:00:00Z");
    Instant lastS = Instant.parse("2026-05-13T12:00:00Z");
    DiscoverySource src =
        DiscoverySource.builder()
            .id(id)
            .sourceKey("src_a")
            .displayName("Sample")
            .sourceType(DiscoverySourceType.CURATED)
            .kind(DiscoverySourceKind.SITEMAP)
            .baseUrl("https://x.test/")
            .enabled(true)
            .userDisabled(false)
            .requestsPerMinute(6)
            .requestsPerDay(500)
            .respectRobotsTxt(true)
            .userAgent("UA/1.0")
            .failureStreak(3)
            .lastFailureAt(lastF)
            .lastSuccessAt(lastS)
            .notes("note")
            .optimisticVersion(5L)
            .build();

    DiscoverySourceMapper m = new DiscoverySourceMapper() {};
    DiscoverySourceDto dto = m.toDto(src);

    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.sourceKey()).isEqualTo("src_a");
    assertThat(dto.displayName()).isEqualTo("Sample");
    assertThat(dto.kind()).isEqualTo(DiscoverySourceKind.SITEMAP);
    assertThat(dto.baseUrl()).isEqualTo("https://x.test/");
    assertThat(dto.enabled()).isTrue();
    assertThat(dto.requestsPerMinute()).isEqualTo(6);
    assertThat(dto.requestsPerDay()).isEqualTo(500);
    assertThat(dto.respectRobotsTxt()).isTrue();
    assertThat(dto.userAgent()).isEqualTo("UA/1.0");
    assertThat(dto.failureStreak()).isEqualTo(3);
    assertThat(dto.lastFailureAt()).isEqualTo(lastF);
    assertThat(dto.lastSuccessAt()).isEqualTo(lastS);
    assertThat(dto.notes()).isEqualTo("note");
    assertThat(dto.optimisticVersion()).isEqualTo(5L);
  }

  @Test
  void sourceMapper_toDtos_nullAndEmpty_returnEmpty() {
    DiscoverySourceMapper m = new DiscoverySourceMapper() {};
    assertThat(m.toDtos(null)).isEmpty();
    assertThat(m.toDtos(Collections.emptyList())).isEmpty();
  }

  @Test
  void sourceMapper_toDtos_nonEmpty_mapsEach() {
    DiscoverySourceMapper m = new DiscoverySourceMapper() {};
    DiscoverySource a = DiscoveryTestData.sampleSource("src_a");
    DiscoverySource b = DiscoveryTestData.sampleSource("src_b");
    assertThat(m.toDtos(List.of(a, b))).hasSize(2);
  }

  // =================== DiscoveryJob + DiscoveryScrapeLog + DiscoverySource Lombok
  // ===================

  @Test
  void discoveryJob_builderAndGettersRoundTrip() {
    DiscoveryJob job =
        DiscoveryJob.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            .userId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .trigger(DiscoveryJobTrigger.USER_INITIATED)
            .requestedCount(8)
            .constraintsJson(objectMapper.createObjectNode().put("schemaVersion", 1))
            .sourcesRequested(new ArrayList<>(List.of("a")))
            .status(DiscoveryJobStatus.PARTIAL)
            .queuedAt(Instant.EPOCH)
            .startedAt(Instant.EPOCH.plusSeconds(1))
            .completedAt(Instant.EPOCH.plusSeconds(2))
            .candidatesSeen(10)
            .candidatesAfterFilter(8)
            .recipesIngested(5)
            .recipesSkippedDuplicate(2)
            .sourcesSucceeded(new ArrayList<>(List.of("a")))
            .sourcesFailed(new ArrayList<>(List.of("b")))
            .errorSummary("err")
            .traceId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
            .optimisticVersion(11L)
            .build();

    assertThat(job.getRequestedCount()).isEqualTo(8);
    assertThat(job.getStatus()).isEqualTo(DiscoveryJobStatus.PARTIAL);
    assertThat(job.getTrigger()).isEqualTo(DiscoveryJobTrigger.USER_INITIATED);
    assertThat(job.getErrorSummary()).isEqualTo("err");
    assertThat(job.getOptimisticVersion()).isEqualTo(11L);

    // exercise setters too — Lombok @Setter
    job.setErrorSummary("err2");
    assertThat(job.getErrorSummary()).isEqualTo("err2");
    job.setCandidatesSeen(99);
    assertThat(job.getCandidatesSeen()).isEqualTo(99);
  }

  @Test
  void discoveryJob_defaultListsAreMutableArrayList() {
    // kills EmptyObjectReturnValsMutator on the Lombok $default$sources* generators — production
    // returns a fresh mutable ArrayList; mutation returns Collections.emptyList() which throws
    // UnsupportedOperationException on add().
    DiscoveryJob job =
        DiscoveryJob.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .trigger(DiscoveryJobTrigger.COLD_START)
            .requestedCount(1)
            .constraintsJson(objectMapper.createObjectNode())
            .status(DiscoveryJobStatus.QUEUED)
            .queuedAt(Instant.now())
            .traceId(UUID.randomUUID())
            .build();

    assertThat(job.getSourcesRequested()).isEmpty();
    assertThat(job.getSourcesSucceeded()).isEmpty();
    assertThat(job.getSourcesFailed()).isEmpty();
    // Add to verify mutability — the immutable Collections.emptyList() mutation would throw here.
    job.getSourcesRequested().add("src_a");
    job.getSourcesSucceeded().add("src_b");
    job.getSourcesFailed().add("src_c");
    assertThat(job.getSourcesRequested()).containsExactly("src_a");
    assertThat(job.getSourcesSucceeded()).containsExactly("src_b");
    assertThat(job.getSourcesFailed()).containsExactly("src_c");
  }

  @Test
  void discoveryScrapeLog_builderAndGetters() {
    UUID id = UUID.randomUUID();
    DiscoveryScrapeLog log =
        DiscoveryScrapeLog.builder()
            .id(id)
            .jobId(UUID.randomUUID())
            .sourceKey("k")
            .candidateUrl("u")
            .canonicalUrl("c")
            .status(ScrapeOutcome.SUCCESS)
            .httpStatusCode(200)
            .robotsTxtOutcome(RobotsTxtOutcome.ALLOWED)
            .latencyMs(50)
            .contentFingerprint("fp")
            .extractionMethod("json_ld")
            .extractionConfidence(new BigDecimal("0.9"))
            .recipeId(UUID.randomUUID())
            .skipReason(ScrapeSkipReason.DUPLICATE)
            .errorClass("E")
            .errorMessage("m")
            .occurredAt(Instant.EPOCH)
            .build();

    assertThat(log.getId()).isEqualTo(id);
    assertThat(log.getStatus()).isEqualTo(ScrapeOutcome.SUCCESS);
    assertThat(log.getHttpStatusCode()).isEqualTo(200);
    assertThat(log.getLatencyMs()).isEqualTo(50);
    assertThat(log.getContentFingerprint()).isEqualTo("fp");
    assertThat(log.getExtractionMethod()).isEqualTo("json_ld");
    assertThat(log.getExtractionConfidence()).isEqualByComparingTo("0.9");
    assertThat(log.getSkipReason()).isEqualTo(ScrapeSkipReason.DUPLICATE);
    assertThat(log.getErrorClass()).isEqualTo("E");
    assertThat(log.getErrorMessage()).isEqualTo("m");
  }

  @Test
  void discoveryJob_timestampAccessors() {
    // Covers getCreatedAt / getUpdatedAt — Lombok-generated getters that are otherwise unexercised.
    DiscoveryJob job = DiscoveryTestData.sampleJob(UUID.randomUUID());
    job.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
    job.setUpdatedAt(Instant.parse("2026-05-02T00:00:00Z"));
    assertThat(job.getCreatedAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    assertThat(job.getUpdatedAt()).isEqualTo(Instant.parse("2026-05-02T00:00:00Z"));
  }

  @Test
  void discoverySource_uncommonAccessors() {
    // Covers getCrawlConfig / getCreatedAt / getLastUsedAt / getSourceType / getUpdatedAt
    // (NullReturnVals getter mutators that otherwise survive).
    com.fasterxml.jackson.databind.node.ObjectNode cfg =
        objectMapper.createObjectNode().put("k", "v");
    com.example.mealprep.discovery.domain.entity.DiscoverySource src =
        com.example.mealprep.discovery.domain.entity.DiscoverySource.builder()
            .id(UUID.randomUUID())
            .sourceKey("src_a")
            .displayName("Sample")
            .sourceType(com.example.mealprep.discovery.domain.entity.DiscoverySourceType.CURATED)
            .kind(DiscoverySourceKind.SITEMAP)
            .baseUrl("https://x.test")
            .enabled(true)
            .requestsPerMinute(6)
            .requestsPerDay(500)
            .respectRobotsTxt(true)
            .userAgent("UA")
            .crawlConfig(cfg)
            .lastUsedAt(Instant.parse("2026-05-12T00:00:00Z"))
            .build();
    src.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
    src.setUpdatedAt(Instant.parse("2026-05-02T00:00:00Z"));

    assertThat(src.getSourceType())
        .isEqualTo(com.example.mealprep.discovery.domain.entity.DiscoverySourceType.CURATED);
    assertThat(src.getCrawlConfig()).isEqualTo(cfg);
    assertThat(src.getCreatedAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    assertThat(src.getUpdatedAt()).isEqualTo(Instant.parse("2026-05-02T00:00:00Z"));
    assertThat(src.getLastUsedAt()).isEqualTo(Instant.parse("2026-05-12T00:00:00Z"));
  }

  @Test
  void discoverySource_settersFlipFlags() {
    DiscoverySource src = DiscoveryTestData.sampleSource("src_a");
    src.setEnabled(false);
    src.setUserDisabled(true);
    src.setFailureStreak(9);
    src.setLastFailureAt(Instant.EPOCH);
    src.setLastSuccessAt(Instant.EPOCH.plusSeconds(1));
    src.setLastUsedAt(Instant.EPOCH.plusSeconds(2));
    src.setNotes("hello");
    src.setRespectRobotsTxt(false);
    src.setRequestsPerMinute(2);
    src.setRequestsPerDay(10);
    src.setQualityScore(new BigDecimal("0.500"));
    src.setUserAgent("UA");
    src.setBaseUrl("https://x.test");

    assertThat(src.isEnabled()).isFalse();
    assertThat(src.isUserDisabled()).isTrue();
    assertThat(src.getFailureStreak()).isEqualTo(9);
    assertThat(src.getNotes()).isEqualTo("hello");
    assertThat(src.isRespectRobotsTxt()).isFalse();
    assertThat(src.getRequestsPerMinute()).isEqualTo(2);
    assertThat(src.getRequestsPerDay()).isEqualTo(10);
    assertThat(src.getUserAgent()).isEqualTo("UA");
    assertThat(src.getQualityScore()).isEqualByComparingTo("0.500");
  }

  @Test
  void discoveryGoogleCseUsage_builderAndGetters() {
    DiscoveryGoogleCseUsage row =
        DiscoveryGoogleCseUsage.builder()
            .day(LocalDate.parse("2026-05-13"))
            .callCount(42)
            .updatedAt(Instant.EPOCH)
            .build();

    assertThat(row.getDay()).isEqualTo(LocalDate.parse("2026-05-13"));
    assertThat(row.getCallCount()).isEqualTo(42);
    assertThat(row.getUpdatedAt()).isEqualTo(Instant.EPOCH);

    row.setCallCount(99);
    row.setUpdatedAt(Instant.EPOCH.plusSeconds(1));
    assertThat(row.getCallCount()).isEqualTo(99);
    assertThat(row.getUpdatedAt()).isEqualTo(Instant.EPOCH.plusSeconds(1));
  }

  // =================== Exceptions ===================

  @Test
  void discoveryJobNotFoundException_carriesJobIdInMessageAndAccessor() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-00000000abcd");
    DiscoveryJobNotFoundException e = new DiscoveryJobNotFoundException(id);
    assertThat(e.getMessage()).contains(id.toString());
    assertThat(e.jobId()).isEqualTo(id); // kills NullReturnValsMutator on jobId() getter.
  }

  @Test
  void discoverySourceNotFoundException_carriesKeyInMessageAndAccessor() {
    DiscoverySourceNotFoundException e = new DiscoverySourceNotFoundException("src_a");
    assertThat(e.getMessage()).contains("src_a");
    assertThat(e.sourceKey()).isEqualTo("src_a"); // kills Empty/Null return mutators on getter.
  }

  @Test
  void discoverySourceUnavailableException_carriesKeyAndMessageAndAccessor() {
    DiscoverySourceUnavailableException e =
        new DiscoverySourceUnavailableException("src_a", "5xx storm", null);
    assertThat(e.getMessage()).contains("src_a").contains("5xx storm");
    assertThat(e.getSourceKey()).isEqualTo("src_a");
  }

  @Test
  void extractionFailedException_carriesUrlInMessageAndAccessor() {
    ExtractionFailedException e = new ExtractionFailedException("https://x.test/r", "no_jsonld");
    assertThat(e.getMessage()).contains("https://x.test/r").contains("no_jsonld");
    assertThat(e.getCandidateUrl()).isEqualTo("https://x.test/r");
  }

  @Test
  void discoveryJobAlreadyTerminalException_exposesJobIdAndStatus() {
    UUID id = UUID.randomUUID();
    DiscoveryJobAlreadyTerminalException e =
        new DiscoveryJobAlreadyTerminalException(id, DiscoveryJobStatus.SUCCEEDED);
    assertThat(e.jobId()).isEqualTo(id);
    assertThat(e.status()).isEqualTo(DiscoveryJobStatus.SUCCEEDED);
    assertThat(e.getMessage()).contains(id.toString()).contains("SUCCEEDED");
  }

  @Test
  void discoveryJobAlreadyTerminalException_runningStatus_carriesInFlightBuildLimitationMessage() {
    // kills EmptyObjectReturnValsMutator at line 26 — the RUNNING branch's detail string must be
    // populated (mutation would replace it with "").
    UUID id = UUID.randomUUID();
    DiscoveryJobAlreadyTerminalException e =
        new DiscoveryJobAlreadyTerminalException(id, DiscoveryJobStatus.RUNNING);
    assertThat(e.getMessage()).contains("cancellation of in-flight jobs").contains(id.toString());
  }

  @Test
  void discoveryConstraintInvalidException_singleArgConstructor_emptyErrors() {
    DiscoveryConstraintInvalidException e = new DiscoveryConstraintInvalidException("bad shape");
    assertThat(e.errors()).isEmpty();
    assertThat(e.getMessage()).isEqualTo("bad shape");
  }

  @Test
  void discoveryConstraintInvalidException_listConstructor_keepsErrors() {
    DiscoveryConstraintInvalidException e =
        new DiscoveryConstraintInvalidException("bad", List.of("a", "b"));
    assertThat(e.errors()).containsExactly("a", "b");
  }

  @Test
  void discoveryConstraintInvalidException_nullErrors_yieldsEmpty() {
    DiscoveryConstraintInvalidException e = new DiscoveryConstraintInvalidException("bad", null);
    assertThat(e.errors()).isEmpty();
  }

  @Test
  void discoveryJobTimeoutException_exposesJobIdAndTimeout() {
    UUID id = UUID.randomUUID();
    DiscoveryJobTimeoutException e = new DiscoveryJobTimeoutException(id, Duration.ofSeconds(60));
    assertThat(e.jobId()).isEqualTo(id);
    assertThat(e.timeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void discoveryAllSourcesUnavailableException_exposesJobIdAndFailedSources() {
    UUID id = UUID.randomUUID();
    DiscoveryAllSourcesUnavailableException e =
        new DiscoveryAllSourcesUnavailableException(id, List.of("a", "b"));
    assertThat(e.jobId()).isEqualTo(id);
    assertThat(e.failedSources()).containsExactly("a", "b");
  }

  @Test
  void discoveryAllSourcesUnavailableException_nullList_isEmpty() {
    UUID id = UUID.randomUUID();
    DiscoveryAllSourcesUnavailableException e =
        new DiscoveryAllSourcesUnavailableException(id, null);
    assertThat(e.failedSources()).isEmpty();
  }

  @Test
  void httpFetchException_exposesStatusCodeAndCause() {
    Exception cause = new RuntimeException("boom");
    HttpFetchException e = new HttpFetchException("HTTP 503", 503, cause);
    assertThat(e.statusCode()).isEqualTo(503);
    assertThat(e.getCause()).isSameAs(cause);
    assertThat(e.getMessage()).isEqualTo("HTTP 503");
  }

  @Test
  void httpFetchException_nullStatus_isNull() {
    HttpFetchException e = new HttpFetchException("network error", null, null);
    assertThat(e.statusCode()).isNull();
  }

  // =================== StartDiscoveryJobRequest cross-field invariant ===================

  @Test
  void startDiscoveryJobRequest_nullConstraints_invariantPasses() {
    // kills NegateConditionalsMutator at line 39 (`constraints == null`).
    com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest req =
        new com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest(
            com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
            5,
            null,
            null,
            null);
    assertThat(req.isMaxRecipesPerSourceWithinTotal()).isTrue();
  }

  @Test
  void startDiscoveryJobRequest_nullMaxRecipesPerSource_invariantPasses() {
    // kills NegateConditionalsMutator at line 39 (`constraints.maxRecipesPerSource() == null`).
    com.example.mealprep.discovery.api.dto.DiscoveryConstraints c =
        new com.example.mealprep.discovery.api.dto.DiscoveryConstraints(
            1, null, null, null, null, null, null, null);
    com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest req =
        new com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest(
            com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
            5,
            c,
            null,
            null);
    assertThat(req.isMaxRecipesPerSourceWithinTotal()).isTrue();
  }

  @Test
  void startDiscoveryJobRequest_maxRecipesEqualsRequestedCount_invariantPasses() {
    // kills ConditionalsBoundaryMutator at line 42 (`<=`). Exactly equal must pass.
    com.example.mealprep.discovery.api.dto.DiscoveryConstraints c =
        new com.example.mealprep.discovery.api.dto.DiscoveryConstraints(
            1, null, null, null, null, null, null, 5);
    com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest req =
        new com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest(
            com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
            5,
            c,
            null,
            null);
    assertThat(req.isMaxRecipesPerSourceWithinTotal()).isTrue();
  }

  @Test
  void startDiscoveryJobRequest_maxRecipesAboveRequestedCount_invariantFails() {
    // kills BooleanTrue/FalseReturnValsMutator + NegateConditionalsMutator at line 42 — the
    // failing branch must return false.
    com.example.mealprep.discovery.api.dto.DiscoveryConstraints c =
        new com.example.mealprep.discovery.api.dto.DiscoveryConstraints(
            1, null, null, null, null, null, null, 10);
    com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest req =
        new com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest(
            com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
            5,
            c,
            null,
            null);
    assertThat(req.isMaxRecipesPerSourceWithinTotal()).isFalse();
  }

  @Test
  void startDiscoveryJobRequest_maxRecipesBelowRequestedCount_invariantPasses() {
    com.example.mealprep.discovery.api.dto.DiscoveryConstraints c =
        new com.example.mealprep.discovery.api.dto.DiscoveryConstraints(
            1, null, null, null, null, null, null, 2);
    com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest req =
        new com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest(
            com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger.COLD_START,
            5,
            c,
            null,
            null);
    assertThat(req.isMaxRecipesPerSourceWithinTotal()).isTrue();
  }
}
