package com.example.mealprep.adaptation.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.entity.AdaptationFingerprint;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round-trips every aggregate / reference entity through its repository to prove:
 *
 * <ul>
 *   <li>{@code ddl-auto=validate} passes against the migrated schema for every entity column
 *   <li>JSONB columns survive Jackson round-trip
 *   <li>{@code String[]} round-trips via hypersistence {@link
 *       io.hypersistence.utils.hibernate.type.array.StringArrayType}
 *   <li>Enum strings persist as the uppercase Java name (matches the DDL column constraints
 *       documented in each migration)
 * </ul>
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationEntitiesJpaIT {

  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private AdaptationTraceRepository traceRepository;
  @Autowired private AdaptationFingerprintRepository fingerprintRepository;
  @Autowired private PlannerHintRecordRepository plannerHintRepository;
  @Autowired private NutritionalKnowledgeRepository nutritionalKnowledgeRepository;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void cleanup() {
    plannerHintRepository.deleteAll();
    pendingChangeRepository.deleteAll();
    traceRepository.deleteAll();
    fingerprintRepository.deleteAll();
    jobRepository.deleteAll();
    nutritionalKnowledgeRepository.deleteAll();
  }

  @Test
  void adaptationJob_persists_and_round_trips_inputs_json() throws Exception {
    UUID id = UUID.randomUUID();
    JsonNode inputs =
        objectMapper.readTree(
            "{\"recipeId\":\"abc\",\"directive\":{\"kind\":\"COST_DELTA\",\"target\":-2.0}}");
    AdaptationJob job = newJob(id, inputs);
    jobRepository.saveAndFlush(job);

    AdaptationJob found = jobRepository.findById(id).orElseThrow();
    assertThat(found.getCatalogue()).isEqualTo(Catalogue.USER);
    assertThat(found.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(found.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(found.getInputs().get("recipeId").asText()).isEqualTo("abc");
    assertThat(found.getInputs().get("directive").get("kind").asText()).isEqualTo("COST_DELTA");
  }

  @Test
  void pendingChange_persists_and_user_edits_starts_null_then_set() throws Exception {
    UUID jobId = UUID.randomUUID();
    jobRepository.saveAndFlush(newJob(jobId, objectMapper.readTree("{}")));

    UUID pcId = UUID.randomUUID();
    PendingChange pc =
        PendingChange.builder()
            .id(pcId)
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .jobId(jobId)
            .traceId(UUID.randomUUID())
            .changeDimension(ChangeDimension.SALT_LEVEL)
            .proposedDiff(objectMapper.readTree("{\"ops\":[]}"))
            .proposedClassification(AdaptationClassification.VERSION)
            .baseVersionId(UUID.randomUUID())
            .baseBranchId(UUID.randomUUID())
            .reasoning("reduced salt")
            .nutritionalNotes(null)
            .confidence(new BigDecimal("0.750"))
            .impactScore(new BigDecimal("0.500"))
            .promptTemplateVersion("v1.0.0")
            .status(PendingChangeStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(14, ChronoUnit.DAYS))
            .build();
    pendingChangeRepository.saveAndFlush(pc);

    PendingChange found = pendingChangeRepository.findById(pcId).orElseThrow();
    assertThat(found.getUserEdits()).isNull();
    assertThat(found.getStatus()).isEqualTo(PendingChangeStatus.PENDING);

    found.setUserEdits(objectMapper.readTree("{\"a\":1,\"b\":2}"));
    pendingChangeRepository.saveAndFlush(found);

    PendingChange reread = pendingChangeRepository.findById(pcId).orElseThrow();
    assertThat(reread.getUserEdits().get("a").asInt()).isEqualTo(1);
    assertThat(reread.getUserEdits().get("b").asInt()).isEqualTo(2);
  }

  @Test
  void adaptationTrace_persists_and_no_version_field() throws Exception {
    UUID jobId = UUID.randomUUID();
    jobRepository.saveAndFlush(newJob(jobId, objectMapper.readTree("{}")));

    UUID traceRowId = UUID.randomUUID();
    AdaptationTrace trace =
        AdaptationTrace.builder()
            .id(traceRowId)
            .jobId(jobId)
            .recipeId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .source(JobSource.IMPORT)
            .promptTemplateName("recipe-adaptation")
            .promptTemplateVersion("v1.0.0")
            .inputsSnapshot(objectMapper.readTree("{\"recipe\":{}}"))
            .candidates(objectMapper.readTree("[]"))
            .validationResult(ValidationResult.NO_CHANGE)
            .outcomeKind(OutcomeKind.NO_OP)
            .durationMs(123)
            .createdAt(Instant.now())
            .build();
    traceRepository.saveAndFlush(trace);

    AdaptationTrace found = traceRepository.findById(traceRowId).orElseThrow();
    assertThat(found.getOutcomeKind()).isEqualTo(OutcomeKind.NO_OP);
    assertThat(found.getRawAiResponse()).isNull();
  }

  @Test
  void adaptationFingerprint_unique_recipe_branch_round_trip() throws Exception {
    UUID id = UUID.randomUUID();
    AdaptationFingerprint fp =
        AdaptationFingerprint.builder()
            .id(id)
            .recipeId(UUID.randomUUID())
            .branchId(UUID.randomUUID())
            .versionId(UUID.randomUUID())
            .bodyHash("abcdef0123456789")
            .fingerprint(objectMapper.readTree("{\"flavour\":\"savoury\"}"))
            .derivedAt(Instant.now())
            .build();
    fingerprintRepository.saveAndFlush(fp);

    AdaptationFingerprint found = fingerprintRepository.findById(id).orElseThrow();
    assertThat(found.getBodyHash()).isEqualTo("abcdef0123456789");
    assertThat(found.getFingerprint().get("flavour").asText()).isEqualTo("savoury");
    assertThat(fingerprintRepository.findByBodyHash("abcdef0123456789")).isPresent();
  }

  @Test
  void plannerHintRecord_persists_invalidated_at_starts_null_then_set() throws Exception {
    UUID id = UUID.randomUUID();
    PlannerHintRecord hint =
        PlannerHintRecord.builder()
            .id(id)
            .recipeId(UUID.randomUUID())
            .versionId(UUID.randomUUID())
            .branchId(UUID.randomUUID())
            .hintType(HintType.PREP_LEAD_TIME)
            .description("needs 8h soak")
            .payload(objectMapper.readTree("{\"lead_time_hours\":8}"))
            .severity(HintSeverity.WARN)
            .traceId(UUID.randomUUID())
            .createdAt(Instant.now())
            .build();
    plannerHintRepository.saveAndFlush(hint);

    PlannerHintRecord found = plannerHintRepository.findById(id).orElseThrow();
    assertThat(found.getInvalidatedAt()).isNull();

    found.setInvalidatedAt(Instant.now());
    plannerHintRepository.saveAndFlush(found);
    assertThat(plannerHintRepository.findById(id).orElseThrow().getInvalidatedAt()).isNotNull();
  }

  @Test
  void nutritionalKnowledgeEntry_round_trips_string_array_and_payload() throws Exception {
    UUID id = UUID.randomUUID();
    NutritionalKnowledgeEntry e =
        NutritionalKnowledgeEntry.builder()
            .id(id)
            .knowledgeKind(KnowledgeKind.PAIRING)
            .subjectKeys(new String[] {"tomato", "basil"})
            .payload(objectMapper.readTree("{\"affinity\":\"strong\"}"))
            .confidenceTier("HIGH")
            .source("manual")
            .createdAt(Instant.now())
            .build();
    nutritionalKnowledgeRepository.saveAndFlush(e);

    NutritionalKnowledgeEntry found = nutritionalKnowledgeRepository.findById(id).orElseThrow();
    assertThat(found.getSubjectKeys()).containsExactly("tomato", "basil");
    assertThat(found.getPayload().get("affinity").asText()).isEqualTo("strong");
  }

  // ---------------- helpers ----------------

  private AdaptationJob newJob(UUID id, JsonNode inputs) {
    return AdaptationJob.builder()
        .id(id)
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .catalogue(Catalogue.USER)
        .source(JobSource.IMPORT)
        .priority(JobPriority.ASYNC)
        .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
        .status(JobStatus.PENDING)
        .inputs(inputs)
        .traceId(UUID.randomUUID())
        .enqueuedAt(Instant.now())
        .build();
  }
}
