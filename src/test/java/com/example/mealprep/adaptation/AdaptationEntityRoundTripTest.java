package com.example.mealprep.adaptation;

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
import com.example.mealprep.adaptation.domain.enums.JobFailureReason;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Builder-&gt;getter round-trip tests for the adaptation JPA entities. Every persisted column has a
 * getter the persistence layer relies on; PIT flagged the getters as untested ("replaced return
 * value with null/0/\"\"" survivors). Each assertion pins a distinct sentinel value per field so a
 * mutated getter (returning null/0/"" instead of the stored value) fails the test. These are
 * data-carrier contracts, not Lombok-for-its-own-sake: a silently-null getter here corrupts a DB
 * write.
 */
class AdaptationEntityRoundTripTest {

  private final JsonNode node = JsonNodeFactory.instance.objectNode().put("k", "v");

  @Test
  void adaptationJob_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID parentDecisionId = UUID.randomUUID();
    Instant enqueued = Instant.parse("2026-01-01T00:00:00Z");
    Instant started = Instant.parse("2026-01-01T00:01:00Z");
    Instant completed = Instant.parse("2026-01-01T00:02:00Z");
    Instant created = Instant.parse("2026-01-01T00:00:30Z");
    Instant updated = Instant.parse("2026-01-01T00:03:00Z");

    AdaptationJob job =
        AdaptationJob.builder()
            .id(id)
            .recipeId(recipeId)
            .userId(userId)
            .catalogue(Catalogue.USER)
            .source(JobSource.FEEDBACK)
            .priority(JobPriority.ASYNC)
            .approvalPolicy(ApprovalPolicy.DIRECT)
            .status(JobStatus.FAILED)
            .failureReason(JobFailureReason.AI_UNAVAILABLE)
            .failureExcerpt("provider 503")
            .inputs(node)
            .promptTemplateVersion("pt-v9")
            .traceId(traceId)
            .parentDecisionId(parentDecisionId)
            .enqueuedAt(enqueued)
            .startedAt(started)
            .completedAt(completed)
            .durationMs(1234)
            .optimisticVersion(7L)
            .createdAt(created)
            .updatedAt(updated)
            .build();

    assertThat(job.getId()).isEqualTo(id);
    assertThat(job.getRecipeId()).isEqualTo(recipeId);
    assertThat(job.getUserId()).isEqualTo(userId);
    assertThat(job.getCatalogue()).isEqualTo(Catalogue.USER);
    assertThat(job.getSource()).isEqualTo(JobSource.FEEDBACK);
    assertThat(job.getPriority()).isEqualTo(JobPriority.ASYNC);
    assertThat(job.getApprovalPolicy()).isEqualTo(ApprovalPolicy.DIRECT);
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getFailureReason()).isEqualTo(JobFailureReason.AI_UNAVAILABLE);
    assertThat(job.getFailureExcerpt()).isEqualTo("provider 503");
    assertThat(job.getInputs()).isSameAs(node);
    assertThat(job.getPromptTemplateVersion()).isEqualTo("pt-v9");
    assertThat(job.getTraceId()).isEqualTo(traceId);
    assertThat(job.getParentDecisionId()).isEqualTo(parentDecisionId);
    assertThat(job.getEnqueuedAt()).isEqualTo(enqueued);
    assertThat(job.getStartedAt()).isEqualTo(started);
    assertThat(job.getCompletedAt()).isEqualTo(completed);
    assertThat(job.getDurationMs()).isEqualTo(1234);
    assertThat(job.getOptimisticVersion()).isEqualTo(7L);
    assertThat(job.getCreatedAt()).isEqualTo(created);
    assertThat(job.getUpdatedAt()).isEqualTo(updated);
  }

  @Test
  void adaptationTrace_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID aiCallId = UUID.randomUUID();
    UUID outcomeTargetId = UUID.randomUUID();
    Instant created = Instant.parse("2026-02-02T02:02:02Z");

    AdaptationTrace t =
        AdaptationTrace.builder()
            .id(id)
            .jobId(jobId)
            .recipeId(recipeId)
            .traceId(traceId)
            .source(JobSource.IMPORT)
            .promptTemplateName("recipe-adaptation")
            .promptTemplateVersion("v3")
            .aiCallId(aiCallId)
            .inputsSnapshot(node)
            .rawAiResponse(node)
            .candidates(node)
            .chosenCandidateIndex(2)
            .classificationDecision(AdaptationClassification.BRANCH)
            .finalDiff(node)
            .confidence(new BigDecimal("0.812"))
            .characterPreservationScore(new BigDecimal("0.945"))
            .validationResult(ValidationResult.PASSED)
            .outcomeKind(OutcomeKind.BRANCH_CREATED)
            .outcomeTargetId(outcomeTargetId)
            .durationMs(777)
            .createdAt(created)
            .build();

    assertThat(t.getId()).isEqualTo(id);
    assertThat(t.getJobId()).isEqualTo(jobId);
    assertThat(t.getRecipeId()).isEqualTo(recipeId);
    assertThat(t.getTraceId()).isEqualTo(traceId);
    assertThat(t.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(t.getPromptTemplateName()).isEqualTo("recipe-adaptation");
    assertThat(t.getPromptTemplateVersion()).isEqualTo("v3");
    assertThat(t.getAiCallId()).isEqualTo(aiCallId);
    assertThat(t.getInputsSnapshot()).isSameAs(node);
    assertThat(t.getRawAiResponse()).isSameAs(node);
    assertThat(t.getCandidates()).isSameAs(node);
    assertThat(t.getChosenCandidateIndex()).isEqualTo(2);
    assertThat(t.getClassificationDecision()).isEqualTo(AdaptationClassification.BRANCH);
    assertThat(t.getFinalDiff()).isSameAs(node);
    assertThat(t.getConfidence()).isEqualByComparingTo("0.812");
    assertThat(t.getCharacterPreservationScore()).isEqualByComparingTo("0.945");
    assertThat(t.getValidationResult()).isEqualTo(ValidationResult.PASSED);
    assertThat(t.getOutcomeKind()).isEqualTo(OutcomeKind.BRANCH_CREATED);
    assertThat(t.getOutcomeTargetId()).isEqualTo(outcomeTargetId);
    assertThat(t.getDurationMs()).isEqualTo(777);
    assertThat(t.getCreatedAt()).isEqualTo(created);
  }

  @Test
  void pendingChange_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    UUID baseVersionId = UUID.randomUUID();
    UUID baseBranchId = UUID.randomUUID();
    UUID supersededBy = UUID.randomUUID();
    UUID acceptedVersionId = UUID.randomUUID();
    Instant created = Instant.parse("2026-03-03T03:03:03Z");
    Instant expires = Instant.parse("2026-03-04T03:03:03Z");
    Instant resolved = Instant.parse("2026-03-03T12:03:03Z");

    PendingChange pc =
        PendingChange.builder()
            .id(id)
            .recipeId(recipeId)
            .userId(userId)
            .jobId(jobId)
            .traceId(traceId)
            .changeDimension(ChangeDimension.SALT_LEVEL)
            .proposedDiff(node)
            .proposedClassification(AdaptationClassification.SUBSTITUTION)
            .baseVersionId(baseVersionId)
            .baseBranchId(baseBranchId)
            .reasoning("less salt requested")
            .nutritionalNotes("sodium -30%")
            .confidence(new BigDecimal("0.700"))
            .impactScore(new BigDecimal("0.250"))
            .promptTemplateVersion("pt-v4")
            .status(PendingChangeStatus.PENDING)
            .supersededBy(supersededBy)
            .acceptedVersionId(acceptedVersionId)
            .userEdits(node)
            .createdAt(created)
            .expiresAt(expires)
            .resolvedAt(resolved)
            .optimisticVersion(3L)
            .build();

    assertThat(pc.getId()).isEqualTo(id);
    assertThat(pc.getRecipeId()).isEqualTo(recipeId);
    assertThat(pc.getUserId()).isEqualTo(userId);
    assertThat(pc.getJobId()).isEqualTo(jobId);
    assertThat(pc.getTraceId()).isEqualTo(traceId);
    assertThat(pc.getChangeDimension()).isEqualTo(ChangeDimension.SALT_LEVEL);
    assertThat(pc.getProposedDiff()).isSameAs(node);
    assertThat(pc.getProposedClassification()).isEqualTo(AdaptationClassification.SUBSTITUTION);
    assertThat(pc.getBaseVersionId()).isEqualTo(baseVersionId);
    assertThat(pc.getBaseBranchId()).isEqualTo(baseBranchId);
    assertThat(pc.getReasoning()).isEqualTo("less salt requested");
    assertThat(pc.getNutritionalNotes()).isEqualTo("sodium -30%");
    assertThat(pc.getConfidence()).isEqualByComparingTo("0.700");
    assertThat(pc.getImpactScore()).isEqualByComparingTo("0.250");
    assertThat(pc.getPromptTemplateVersion()).isEqualTo("pt-v4");
    assertThat(pc.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
    assertThat(pc.getSupersededBy()).isEqualTo(supersededBy);
    assertThat(pc.getAcceptedVersionId()).isEqualTo(acceptedVersionId);
    assertThat(pc.getUserEdits()).isSameAs(node);
    assertThat(pc.getCreatedAt()).isEqualTo(created);
    assertThat(pc.getExpiresAt()).isEqualTo(expires);
    assertThat(pc.getResolvedAt()).isEqualTo(resolved);
    assertThat(pc.getOptimisticVersion()).isEqualTo(3L);
  }

  @Test
  void adaptationFingerprint_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID derivedByJobId = UUID.randomUUID();
    Instant derivedAt = Instant.parse("2026-04-04T04:04:04Z");

    AdaptationFingerprint fp =
        AdaptationFingerprint.builder()
            .id(id)
            .recipeId(recipeId)
            .branchId(branchId)
            .versionId(versionId)
            .bodyHash("sha256:abc")
            .fingerprint(node)
            .derivedByJobId(derivedByJobId)
            .derivedAt(derivedAt)
            .build();

    assertThat(fp.getId()).isEqualTo(id);
    assertThat(fp.getRecipeId()).isEqualTo(recipeId);
    assertThat(fp.getBranchId()).isEqualTo(branchId);
    assertThat(fp.getVersionId()).isEqualTo(versionId);
    assertThat(fp.getBodyHash()).isEqualTo("sha256:abc");
    assertThat(fp.getFingerprint()).isSameAs(node);
    assertThat(fp.getDerivedByJobId()).isEqualTo(derivedByJobId);
    assertThat(fp.getDerivedAt()).isEqualTo(derivedAt);
  }

  @Test
  void nutritionalKnowledgeEntry_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    Instant created = Instant.parse("2026-05-05T05:05:05Z");
    String[] keys = {"iron", "lemon"};

    NutritionalKnowledgeEntry e =
        NutritionalKnowledgeEntry.builder()
            .id(id)
            .knowledgeKind(KnowledgeKind.PAIRING)
            .subjectKeys(keys)
            .payload(node)
            .confidenceTier("MEDIUM")
            .source("curated")
            .createdAt(created)
            .build();

    assertThat(e.getId()).isEqualTo(id);
    assertThat(e.getKnowledgeKind()).isEqualTo(KnowledgeKind.PAIRING);
    assertThat(e.getSubjectKeys()).containsExactly("iron", "lemon");
    assertThat(e.getPayload()).isSameAs(node);
    assertThat(e.getConfidenceTier()).isEqualTo("MEDIUM");
    assertThat(e.getSource()).isEqualTo("curated");
    assertThat(e.getCreatedAt()).isEqualTo(created);
  }

  @Test
  void plannerHintRecord_round_trips_every_field() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID emittedByJobId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant created = Instant.parse("2026-06-06T06:06:06Z");
    Instant invalidated = Instant.parse("2026-06-07T06:06:06Z");

    PlannerHintRecord h =
        PlannerHintRecord.builder()
            .id(id)
            .recipeId(recipeId)
            .versionId(versionId)
            .branchId(branchId)
            .hintType(HintType.ABSORPTION_CONFLICT)
            .description("calcium blocks iron")
            .payload(node)
            .severity(HintSeverity.WARN)
            .emittedByJobId(emittedByJobId)
            .traceId(traceId)
            .createdAt(created)
            .invalidatedAt(invalidated)
            .build();

    assertThat(h.getId()).isEqualTo(id);
    assertThat(h.getRecipeId()).isEqualTo(recipeId);
    assertThat(h.getVersionId()).isEqualTo(versionId);
    assertThat(h.getBranchId()).isEqualTo(branchId);
    assertThat(h.getHintType()).isEqualTo(HintType.ABSORPTION_CONFLICT);
    assertThat(h.getDescription()).isEqualTo("calcium blocks iron");
    assertThat(h.getPayload()).isSameAs(node);
    assertThat(h.getSeverity()).isEqualTo(HintSeverity.WARN);
    assertThat(h.getEmittedByJobId()).isEqualTo(emittedByJobId);
    assertThat(h.getTraceId()).isEqualTo(traceId);
    assertThat(h.getCreatedAt()).isEqualTo(created);
    assertThat(h.getInvalidatedAt()).isEqualTo(invalidated);
  }
}
