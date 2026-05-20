package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.PlannerHintDto;
import com.example.mealprep.adaptation.api.mapper.AdaptationJobMapper;
import com.example.mealprep.adaptation.api.mapper.AdaptationTraceMapper;
import com.example.mealprep.adaptation.api.mapper.PlannerHintMapper;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PlannerHintRecord;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Unit-tests the 12 {@code AdaptationQueryService} bodies wired in 01f. */
class AdaptationQueryServiceImplTest {

  private final AdaptationJobRepository jobRepo = Mockito.mock(AdaptationJobRepository.class);
  private final PendingChangeRepository pcRepo = Mockito.mock(PendingChangeRepository.class);
  private final AdaptationTraceRepository traceRepo = Mockito.mock(AdaptationTraceRepository.class);
  private final AdaptationFingerprintRepository fpRepo =
      Mockito.mock(AdaptationFingerprintRepository.class);
  private final PlannerHintRecordRepository hintRepo =
      Mockito.mock(PlannerHintRecordRepository.class);
  private final NutritionalKnowledgeRepository nkRepo =
      Mockito.mock(NutritionalKnowledgeRepository.class);
  private final AdaptationJobMapper jobMapper = Mockito.mock(AdaptationJobMapper.class);
  private final AdaptationTraceMapper traceMapper = Mockito.mock(AdaptationTraceMapper.class);
  private final PlannerHintMapper hintMapper = Mockito.mock(PlannerHintMapper.class);

  private AdaptationServiceImpl service() {
    return new AdaptationServiceImpl(
        jobRepo,
        pcRepo,
        traceRepo,
        fpRepo,
        hintRepo,
        nkRepo,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Mockito.mock(ApplicationEventPublisher.class),
        null,
        null,
        null,
        null,
        null,
        null,
        jobMapper,
        traceMapper,
        hintMapper,
        null);
  }

  private static AdaptationJob job(UUID id) {
    return AdaptationJob.builder()
        .id(id)
        .recipeId(UUID.randomUUID())
        .userId(UUID.randomUUID())
        .status(JobStatus.DONE)
        .build();
  }

  private static AdaptationJobDto jobDto(UUID id) {
    return new AdaptationJobDto(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        JobStatus.DONE,
        null,
        null,
        JsonNodeFactory.instance.objectNode(),
        null,
        UUID.randomUUID(),
        null,
        java.time.Instant.now(),
        null,
        null,
        null,
        0L);
  }

  @Test
  void getJob_maps_when_present_and_empty_when_absent() {
    UUID id = UUID.randomUUID();
    when(jobRepo.findById(id)).thenReturn(Optional.of(job(id)));
    when(jobMapper.toDto(any())).thenReturn(jobDto(id));
    assertThat(service().getJob(id)).isPresent();

    UUID missing = UUID.randomUUID();
    when(jobRepo.findById(missing)).thenReturn(Optional.empty());
    assertThat(service().getJob(missing)).isEmpty();
  }

  @Test
  void getJobsForRecipe_pages_through_mapper() {
    UUID rid = UUID.randomUUID();
    Pageable p = PageRequest.of(0, 20);
    when(jobRepo.findByRecipeIdOrderByEnqueuedAtDesc(rid, p))
        .thenReturn(new PageImpl<>(List.of(job(UUID.randomUUID())), p, 1));
    when(jobMapper.toDto(any())).thenReturn(jobDto(UUID.randomUUID()));
    assertThat(service().getJobsForRecipe(rid, p).getTotalElements()).isEqualTo(1);
  }

  @Test
  void getActiveJobsForUser_filters_pending_and_running() {
    UUID uid = UUID.randomUUID();
    Pageable p = PageRequest.of(0, 20);
    when(jobRepo.findByUserIdAndStatusInOrderByEnqueuedAtDesc(any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(), p, 0));
    assertThat(service().getActiveJobsForUser(uid, p).getTotalElements()).isZero();
  }

  @Test
  void getTraceForJob_empty_when_no_trace() {
    UUID jid = UUID.randomUUID();
    when(traceRepo.findByJobId(jid)).thenReturn(Optional.empty());
    assertThat(service().getTraceForJob(jid)).isEmpty();
  }

  @Test
  void getActiveHintsForVersion_maps_list() {
    UUID vid = UUID.randomUUID();
    when(hintRepo.findActiveForVersion(vid)).thenReturn(List.of(hint(vid)));
    when(hintMapper.toDto(any()))
        .thenReturn(
            new PlannerHintDto(
                UUID.randomUUID(),
                HintType.PREP_LEAD_TIME,
                "d",
                JsonNodeFactory.instance.objectNode(),
                HintSeverity.INFO));
    assertThat(service().getActiveHintsForVersion(vid)).hasSize(1);
  }

  @Test
  void getActiveHintsForVersions_groups_by_version_and_omits_empty() {
    UUID v1 = UUID.randomUUID();
    UUID v2 = UUID.randomUUID();
    when(hintRepo.findActiveForVersions(List.of(v1, v2))).thenReturn(List.of(hint(v1), hint(v1)));
    when(hintMapper.toDto(any()))
        .thenReturn(
            new PlannerHintDto(
                UUID.randomUUID(),
                HintType.PREP_LEAD_TIME,
                "d",
                JsonNodeFactory.instance.objectNode(),
                HintSeverity.INFO));
    var map = service().getActiveHintsForVersions(List.of(v1, v2));
    assertThat(map).containsOnlyKeys(v1);
    assertThat(map.get(v1)).hasSize(2);
  }

  @Test
  void getActiveHintsForVersions_empty_input_returns_empty_map() {
    assertThat(service().getActiveHintsForVersions(List.of())).isEmpty();
  }

  @Test
  void getMostRecentResultForRecipe_empty_when_no_done_job() {
    UUID rid = UUID.randomUUID();
    when(jobRepo.findMostRecentDoneForRecipe(any(), any())).thenReturn(List.of());
    assertThat(service().getMostRecentResultForRecipe(rid)).isEmpty();
  }

  @Test
  void getMostRecentResultForRecipe_present_when_done_job_exists() {
    UUID rid = UUID.randomUUID();
    UUID jid = UUID.randomUUID();
    when(jobRepo.findMostRecentDoneForRecipe(any(), any())).thenReturn(List.of(job(jid)));
    when(traceRepo.findByJobId(jid)).thenReturn(Optional.empty());
    assertThat(service().getMostRecentResultForRecipe(rid)).isPresent();
  }

  @Test
  void getTracesForPromptVersion_pages_through_mapper() {
    Pageable p = PageRequest.of(0, 20);
    when(traceRepo.findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc(
            "n", "v", p))
        .thenReturn(new PageImpl<>(List.of(), p, 0));
    assertThat(service().getTracesForPromptVersion("n", "v", p).getTotalElements()).isZero();
  }

  @Test
  void getRunHistory_filters_by_source_and_window() {
    Pageable p = PageRequest.of(0, 20);
    when(jobRepo.findBySourceAndEnqueuedAtBetweenOrderByEnqueuedAtDesc(any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(List.of(), p, 0));
    assertThat(
            service()
                .getRunHistory(
                    com.example.mealprep.adaptation.domain.enums.JobSource.FEEDBACK,
                    java.time.Instant.now().minusSeconds(3600),
                    java.time.Instant.now(),
                    p)
                .getTotalElements())
        .isZero();
  }

  private static PlannerHintRecord hint(UUID versionId) {
    return PlannerHintRecord.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .versionId(versionId)
        .branchId(UUID.randomUUID())
        .hintType(HintType.PREP_LEAD_TIME)
        .description("d")
        .payload(JsonNodeFactory.instance.objectNode())
        .severity(HintSeverity.INFO)
        .traceId(UUID.randomUUID())
        .createdAt(java.time.Instant.now())
        .build();
  }
}
