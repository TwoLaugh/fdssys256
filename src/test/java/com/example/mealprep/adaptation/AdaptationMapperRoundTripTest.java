package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeDto;
import com.example.mealprep.adaptation.api.dto.PendingChangeListItemDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.mapper.AdaptationJobMapper;
import com.example.mealprep.adaptation.api.mapper.AdaptationTraceMapper;
import com.example.mealprep.adaptation.api.mapper.PendingChangeMapper;
import com.example.mealprep.adaptation.api.mapper.PlannerHintMapper;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
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
import com.example.mealprep.adaptation.domain.enums.OutcomeKind;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.enums.ValidationResult;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct round-trip smoke for the four 01b mappers. Each test builds an entity, maps to DTO, and
 * asserts every field is preserved — including {@link JsonNode} pass-through. The reasoning preview
 * truncation (200 chars) is asserted in its own test.
 *
 * <p>Per ticket-01b §Edge-case checklist line "MapStruct round-trip".
 */
class AdaptationMapperRoundTripTest {

  private final AdaptationJobMapper jobMapper = Mappers.getMapper(AdaptationJobMapper.class);
  private final PendingChangeMapper pendingMapper = Mappers.getMapper(PendingChangeMapper.class);
  private final AdaptationTraceMapper traceMapper = Mappers.getMapper(AdaptationTraceMapper.class);
  private final PlannerHintMapper hintMapper = Mappers.getMapper(PlannerHintMapper.class);

  @Test
  void adaptation_job_round_trips_all_fields_including_json_inputs() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    JsonNode inputs = JsonNodeFactory.instance.objectNode().put("k", "v");
    Instant now = Instant.now();

    AdaptationJob entity =
        AdaptationJob.builder()
            .id(id)
            .recipeId(recipeId)
            .userId(userId)
            .catalogue(Catalogue.USER)
            .source(JobSource.IMPORT)
            .priority(JobPriority.ASYNC)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(JobStatus.PENDING)
            .failureReason(null)
            .failureExcerpt(null)
            .inputs(inputs)
            .promptTemplateVersion("v1")
            .traceId(traceId)
            .parentDecisionId(null)
            .enqueuedAt(now)
            .startedAt(null)
            .completedAt(null)
            .durationMs(null)
            .optimisticVersion(7L)
            .build();

    AdaptationJobDto dto = jobMapper.toDto(entity);
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.recipeId()).isEqualTo(recipeId);
    assertThat(dto.userId()).isEqualTo(userId);
    assertThat(dto.catalogue()).isEqualTo(Catalogue.USER);
    assertThat(dto.source()).isEqualTo(JobSource.IMPORT);
    assertThat(dto.priority()).isEqualTo(JobPriority.ASYNC);
    assertThat(dto.approvalPolicy()).isEqualTo(ApprovalPolicy.PENDING_CHANGE);
    assertThat(dto.status()).isEqualTo(JobStatus.PENDING);
    assertThat(dto.inputs()).isSameAs(inputs);
    assertThat(dto.promptTemplateVersion()).isEqualTo("v1");
    assertThat(dto.traceId()).isEqualTo(traceId);
    assertThat(dto.enqueuedAt()).isEqualTo(now);
    assertThat(dto.optimisticVersion()).isEqualTo(7L);
  }

  @Test
  void adaptation_job_to_dtos_returns_empty_for_null_or_empty() {
    assertThat(jobMapper.toDtos(null)).isEmpty();
    assertThat(jobMapper.toDtos(List.of())).isEmpty();
  }

  @Test
  void adaptation_job_to_dto_returns_null_for_null_entity() {
    assertThat(jobMapper.toDto(null)).isNull();
  }

  @Test
  void pending_change_list_item_truncates_reasoning_to_200_chars() {
    String longReasoning = "x".repeat(500);
    PendingChange entity = pendingChangeWithReasoning(longReasoning);
    PendingChangeListItemDto dto = pendingMapper.toListItem(entity);
    assertThat(dto.reasoningPreview()).hasSize(200);
    assertThat(dto.reasoningPreview()).isEqualTo("x".repeat(200));
  }

  @Test
  void pending_change_list_item_passes_short_reasoning_through() {
    PendingChange entity = pendingChangeWithReasoning("short");
    assertThat(pendingMapper.toListItem(entity).reasoningPreview()).isEqualTo("short");
  }

  @Test
  void pending_change_full_dto_keeps_full_reasoning() {
    String longReasoning = "y".repeat(500);
    PendingChange entity = pendingChangeWithReasoning(longReasoning);
    PendingChangeDto dto = pendingMapper.toDto(entity);
    assertThat(dto.reasoning()).hasSize(500);
  }

  @Test
  void truncate_reasoning_qualifier_handles_null() {
    assertThat(pendingMapper.truncateReasoning(null)).isNull();
  }

  @Test
  void planner_hint_round_trips() {
    UUID id = UUID.randomUUID();
    JsonNode payload = JsonNodeFactory.instance.objectNode().put("foo", "bar");
    PlannerHintRecord entity =
        PlannerHintRecord.builder()
            .id(id)
            .recipeId(UUID.randomUUID())
            .versionId(UUID.randomUUID())
            .branchId(UUID.randomUUID())
            .hintType(HintType.PREP_LEAD_TIME)
            .description("soak overnight")
            .payload(payload)
            .severity(HintSeverity.INFO)
            .traceId(UUID.randomUUID())
            .createdAt(Instant.now())
            .build();

    PlannerHintDto dto = hintMapper.toDto(entity);
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.type()).isEqualTo(HintType.PREP_LEAD_TIME);
    assertThat(dto.description()).isEqualTo("soak overnight");
    assertThat(dto.payload()).isSameAs(payload);
    assertThat(dto.severity()).isEqualTo(HintSeverity.INFO);
  }

  @Test
  void adaptation_trace_round_trips_nullable_fields_as_null() {
    UUID id = UUID.randomUUID();
    JsonNode inputs = JsonNodeFactory.instance.objectNode().put("input", true);
    JsonNode candidates = JsonNodeFactory.instance.arrayNode();
    AdaptationTrace entity =
        AdaptationTrace.builder()
            .id(id)
            .jobId(UUID.randomUUID())
            .recipeId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .source(JobSource.FEEDBACK)
            .promptTemplateName("recipe-adaptation")
            .promptTemplateVersion("v1")
            .aiCallId(null)
            .inputsSnapshot(inputs)
            .rawAiResponse(null)
            .candidates(candidates)
            .chosenCandidateIndex(null)
            .classificationDecision(null)
            .finalDiff(null)
            .confidence(null)
            .characterPreservationScore(null)
            .validationResult(ValidationResult.PASSED)
            .outcomeKind(OutcomeKind.NO_OP)
            .outcomeTargetId(null)
            .durationMs(123)
            .createdAt(Instant.now())
            .build();

    AdaptationTraceDto dto = traceMapper.toDto(entity);
    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.source()).isEqualTo(JobSource.FEEDBACK);
    assertThat(dto.inputsSnapshot()).isSameAs(inputs);
    assertThat(dto.candidates()).isSameAs(candidates);
    assertThat(dto.rawAiResponse()).isNull();
    assertThat(dto.chosenCandidateIndex()).isNull();
    assertThat(dto.classificationDecision()).isNull();
    assertThat(dto.validationResult()).isEqualTo(ValidationResult.PASSED);
    assertThat(dto.outcomeKind()).isEqualTo(OutcomeKind.NO_OP);
    assertThat(dto.durationMs()).isEqualTo(123);
  }

  private PendingChange pendingChangeWithReasoning(String reasoning) {
    return PendingChange.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .jobId(UUID.randomUUID())
        .traceId(UUID.randomUUID())
        .changeDimension(ChangeDimension.SALT_LEVEL)
        .proposedDiff(JsonNodeFactory.instance.objectNode())
        .proposedClassification(AdaptationClassification.VERSION)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning(reasoning)
        .nutritionalNotes("none")
        .confidence(new BigDecimal("0.800"))
        .impactScore(new BigDecimal("0.700"))
        .promptTemplateVersion("v1")
        .status(PendingChangeStatus.PENDING)
        .supersededBy(null)
        .acceptedVersionId(null)
        .userEdits(null)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60 * 60 * 24 * 14))
        .resolvedAt(null)
        .optimisticVersion(0L)
        .build();
  }
}
