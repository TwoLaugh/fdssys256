package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.adaptation.api.dto.DataModelChangeType;
import com.example.mealprep.adaptation.api.dto.DataModelJobRequest;
import com.example.mealprep.adaptation.api.dto.ImportJobRequest;
import com.example.mealprep.adaptation.api.mapper.PendingChangeMapper;
import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.repository.AdaptationFingerprintRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationJobRepository;
import com.example.mealprep.adaptation.domain.repository.AdaptationTraceRepository;
import com.example.mealprep.adaptation.domain.repository.NutritionalKnowledgeRepository;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationServiceImpl;
import com.example.mealprep.adaptation.domain.service.internal.JobReadyEvent;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.example.mealprep.recipe.spi.RecipeWriteApi;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** Unit tests for the 4 trigger entry methods on {@link AdaptationServiceImpl}. */
class AdaptationServiceTriggerEntriesTest {

  @Test
  void enqueueImportJob_user_catalogue_sets_pending_change_policy_and_publishes_JobReadyEvent() {
    AdaptationJobRepository jobRepo = mock(AdaptationJobRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(jobRepo.saveAndFlush(any(AdaptationJob.class))).thenAnswer(inv -> inv.getArgument(0));

    AdaptationServiceImpl svc = svc(jobRepo, events);
    ImportJobRequest req =
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Catalogue.USER,
            DataQuality.AI_GENERATED,
            null,
            null);

    UUID jobId = svc.enqueueImportJob(req);

    assertThat(jobId).isNotNull();
    ArgumentCaptor<AdaptationJob> capt = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(jobRepo).saveAndFlush(capt.capture());
    AdaptationJob saved = capt.getValue();
    assertThat(saved.getSource()).isEqualTo(JobSource.IMPORT);
    assertThat(saved.getPriority()).isEqualTo(JobPriority.ASYNC);
    assertThat(saved.getApprovalPolicy()).isEqualTo(ApprovalPolicy.PENDING_CHANGE);
    assertThat(saved.getStatus()).isEqualTo(JobStatus.PENDING);
    verify(events).publishEvent(any(JobReadyEvent.class));
  }

  @Test
  void enqueueImportJob_system_catalogue_sets_direct_policy() {
    AdaptationJobRepository jobRepo = mock(AdaptationJobRepository.class);
    when(jobRepo.saveAndFlush(any(AdaptationJob.class))).thenAnswer(inv -> inv.getArgument(0));
    AdaptationServiceImpl svc = svc(jobRepo, mock(ApplicationEventPublisher.class));

    svc.enqueueImportJob(
        new ImportJobRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Catalogue.SYSTEM,
            DataQuality.AI_GENERATED,
            null,
            null));

    ArgumentCaptor<AdaptationJob> capt = ArgumentCaptor.forClass(AdaptationJob.class);
    verify(jobRepo).saveAndFlush(capt.capture());
    assertThat(capt.getValue().getApprovalPolicy()).isEqualTo(ApprovalPolicy.DIRECT);
  }

  @Test
  void enqueueDataModelChangeJobs_inserts_N_batch_priority_jobs_no_event() {
    AdaptationJobRepository jobRepo = mock(AdaptationJobRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    when(jobRepo.saveAll(any())).thenAnswer(inv -> List.copyOf(inv.getArgument(0)));

    AdaptationServiceImpl svc = svc(jobRepo, events);
    Set<UUID> affected = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      affected.add(UUID.randomUUID());
    }
    DataModelJobRequest req =
        new DataModelJobRequest(
            UUID.randomUUID(),
            DataModelChangeType.PREFERENCE,
            JsonNodeFactory.instance.objectNode(),
            affected,
            UUID.randomUUID());

    List<UUID> ids = svc.enqueueDataModelChangeJobs(req);

    assertThat(ids).hasSize(5);
    // No JobReadyEvent — BATCH is picked up by the orchestrator's cron.
    org.mockito.Mockito.verify(events, org.mockito.Mockito.never())
        .publishEvent(any(JobReadyEvent.class));
  }

  /** Helper — assembles an impl with only the constructors fields needed by enqueue methods. */
  private static AdaptationServiceImpl svc(
      AdaptationJobRepository jobRepo, ApplicationEventPublisher events) {
    return new AdaptationServiceImpl(
        jobRepo,
        mock(PendingChangeRepository.class),
        mock(AdaptationTraceRepository.class),
        mock(AdaptationFingerprintRepository.class),
        mock(PlannerHintRecordRepository.class),
        mock(NutritionalKnowledgeRepository.class),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        events,
        mock(RecipeWriteApi.class),
        mock(PendingChangeMapper.class),
        config(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static AdaptationConfig config() {
    return new AdaptationConfig(
        5,
        10_000,
        8_000,
        12_000,
        3,
        3,
        14,
        new BigDecimal("0.50"),
        new BigDecimal("2.00"),
        null,
        30,
        "0 0 4 * * *",
        "0 30 4 * * *");
  }
}
