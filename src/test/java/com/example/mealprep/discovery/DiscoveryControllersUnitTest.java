package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.discovery.api.controller.DiscoveryAdminController;
import com.example.mealprep.discovery.api.controller.DiscoveryJobsController;
import com.example.mealprep.discovery.api.controller.DiscoverySourcesController;
import com.example.mealprep.discovery.api.dto.DiscoveryJobDto;
import com.example.mealprep.discovery.api.dto.DiscoveryScrapeLogEntryDto;
import com.example.mealprep.discovery.api.dto.DiscoverySourceDto;
import com.example.mealprep.discovery.api.dto.OrphanSweepResultDto;
import com.example.mealprep.discovery.api.dto.StartDiscoveryJobRequest;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobStatus;
import com.example.mealprep.discovery.domain.entity.DiscoveryJobTrigger;
import com.example.mealprep.discovery.domain.entity.DiscoverySourceKind;
import com.example.mealprep.discovery.domain.service.DiscoveryQueryService;
import com.example.mealprep.discovery.domain.service.DiscoveryService;
import com.example.mealprep.discovery.exception.DiscoveryAllSourcesUnavailableException;
import com.example.mealprep.discovery.exception.DiscoveryJobNotFoundException;
import com.example.mealprep.discovery.exception.DiscoveryJobTimeoutException;
import com.example.mealprep.discovery.exception.DiscoverySourceNotFoundException;
import com.example.mealprep.discovery.testdata.DiscoveryTestData;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for the three discovery controllers + the auth-required guard. Construct each
 * controller directly with mocked collaborators (no Spring context — these are pure unit tests so
 * Pitest can mutate the controller bytecode).
 */
@ExtendWith(MockitoExtension.class)
class DiscoveryControllersUnitTest {

  @Mock private DiscoveryService discoveryService;
  @Mock private DiscoveryQueryService discoveryQueryService;
  @Mock private CurrentUserResolver currentUserResolver;

  private static final UUID USER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

  // ============================ DiscoveryJobsController ============================

  @Test
  void jobsController_start_authedUser_returns202_locationHeader_andBody() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto returned = sampleJobDto(DiscoveryJobStatus.QUEUED);
    when(discoveryService.startJob(eq(USER_ID), any(StartDiscoveryJobRequest.class)))
        .thenReturn(returned);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    ResponseEntity<DiscoveryJobDto> resp = controller.start(req);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(resp.getHeaders().getLocation()).isNotNull();
    assertThat(resp.getHeaders().getLocation().toString()).contains(returned.id().toString());
    assertThat(resp.getBody()).isSameAs(returned);
  }

  @Test
  void jobsController_start_anonymous_throws401() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    assertThatThrownBy(() -> controller.start(req))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("401");
    verify(discoveryService, never()).startJob(any(), any());
  }

  @Test
  void jobsController_list_delegatesWithCallerPageable() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.listJobsForUser(eq(USER_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sampleJobDto(DiscoveryJobStatus.QUEUED))));

    var result = controller.list(0, 20);

    assertThat(result.getContent()).hasSize(1);
    verify(discoveryQueryService, times(1)).listJobsForUser(eq(USER_ID), eq(PageRequest.of(0, 20)));
  }

  @Test
  void jobsController_getById_unknownJob_throws404() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    UUID jobId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getJobForUser(USER_ID, jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getById(jobId))
        .isInstanceOf(DiscoveryJobNotFoundException.class);
  }

  @Test
  void jobsController_getById_known_returnsDto() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    UUID jobId = UUID.randomUUID();
    DiscoveryJobDto dto = sampleJobDto(DiscoveryJobStatus.SUCCEEDED);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getJobForUser(USER_ID, jobId)).thenReturn(Optional.of(dto));

    assertThat(controller.getById(jobId)).isSameAs(dto);
  }

  @Test
  void jobsController_cancel_callsServiceThenReturnsUpdatedDto() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    UUID jobId = UUID.randomUUID();
    DiscoveryJobDto dto = sampleJobDto(DiscoveryJobStatus.FAILED);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getJobForUser(USER_ID, jobId)).thenReturn(Optional.of(dto));

    DiscoveryJobDto result = controller.cancel(jobId);

    verify(discoveryService, times(1)).cancelJob(USER_ID, jobId);
    assertThat(result).isSameAs(dto);
  }

  @Test
  void jobsController_cancel_postReadEmpty_throws404() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    UUID jobId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getJobForUser(USER_ID, jobId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.cancel(jobId))
        .isInstanceOf(DiscoveryJobNotFoundException.class);
  }

  @Test
  void jobsController_scrapeLog_delegates() {
    DiscoveryJobsController controller =
        new DiscoveryJobsController(discoveryService, discoveryQueryService, currentUserResolver);
    UUID jobId = UUID.randomUUID();
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getScrapeLog(eq(jobId), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.<DiscoveryScrapeLogEntryDto>of()));

    var result = controller.scrapeLog(jobId, 0, 20);
    assertThat(result).isNotNull();
    verify(discoveryQueryService, times(1)).getScrapeLog(eq(jobId), eq(PageRequest.of(0, 20)));
  }

  // ============================ DiscoverySourcesController ============================

  @Test
  void sourcesController_list_authedUser_returnsList() {
    DiscoverySourcesController controller =
        new DiscoverySourcesController(discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoverySourceDto dto = sampleSourceDto("src_a");
    when(discoveryQueryService.listSources()).thenReturn(List.of(dto));

    List<DiscoverySourceDto> result = controller.list();

    assertThat(result).containsExactly(dto);
  }

  @Test
  void sourcesController_list_anonymous_throws401() {
    DiscoverySourcesController controller =
        new DiscoverySourcesController(discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    assertThatThrownBy(controller::list).isInstanceOf(ResponseStatusException.class);
    verify(discoveryQueryService, never()).listSources();
  }

  @Test
  void sourcesController_getByKey_unknown_throws404() {
    DiscoverySourcesController controller =
        new DiscoverySourcesController(discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryQueryService.getSource("nope")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getByKey("nope"))
        .isInstanceOf(DiscoverySourceNotFoundException.class);
  }

  @Test
  void sourcesController_getByKey_known_returnsDto() {
    DiscoverySourcesController controller =
        new DiscoverySourcesController(discoveryQueryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoverySourceDto dto = sampleSourceDto("src_a");
    when(discoveryQueryService.getSource("src_a")).thenReturn(Optional.of(dto));

    assertThat(controller.getByKey("src_a")).isSameAs(dto);
  }

  // ============================ DiscoveryAdminController ============================

  @Test
  void adminController_enable_callsService() {
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoverySourceDto dto = sampleSourceDto("src_a");
    when(discoveryService.enableSource("src_a")).thenReturn(dto);

    assertThat(controller.enable("src_a")).isSameAs(dto);
  }

  @Test
  void adminController_disable_callsService() {
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoverySourceDto dto = sampleSourceDto("src_a");
    when(discoveryService.disableSource("src_a")).thenReturn(dto);

    assertThat(controller.disable("src_a")).isSameAs(dto);
  }

  @Test
  void adminController_runOrphanSweep_callsService() {
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(discoveryService.runOrphanSweep()).thenReturn(new OrphanSweepResultDto(5));

    assertThat(controller.runOrphanSweep().resumedCount()).isEqualTo(5);
  }

  @Test
  void adminController_runJobSync_succeeded_returnsOk() {
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto = sampleJobDto(DiscoveryJobStatus.SUCCEEDED);
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    ResponseEntity<DiscoveryJobDto> resp = controller.runJobSync(req, 60, false);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).isSameAs(dto);
  }

  @Test
  void adminController_runJobSync_runningStrictTimeout_throws408() {
    // kills NegateConditionalsMutator on the strictTimeout check.
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto = sampleJobDto(DiscoveryJobStatus.RUNNING);
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    assertThatThrownBy(() -> controller.runJobSync(req, 60, true))
        .isInstanceOf(DiscoveryJobTimeoutException.class);
  }

  @Test
  void adminController_runJobSync_runningNotStrict_returnsOk() {
    // Complements the strict path — non-strict timeout returns the partial DTO with 200.
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto = sampleJobDto(DiscoveryJobStatus.RUNNING);
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    ResponseEntity<DiscoveryJobDto> resp = controller.runJobSync(req, 60, false);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void adminController_runJobSync_failedAllDown_throwsAllSourcesUnavailable() {
    // kills the and-conditional `anyFailed && noneSucceeded`. With only failed sources we throw.
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto =
        sampleJobDtoWithSources(DiscoveryJobStatus.FAILED, List.of(), List.of("a"));
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    assertThatThrownBy(() -> controller.runJobSync(req, 60, false))
        .isInstanceOf(DiscoveryAllSourcesUnavailableException.class);
  }

  @Test
  void adminController_runJobSync_failedSomeSucceeded_returnsOk() {
    // Complements the all-down case — non-all-down FAILED returns 200 (planner inspects).
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto =
        sampleJobDtoWithSources(DiscoveryJobStatus.FAILED, List.of("ok"), List.of("a"));
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    ResponseEntity<DiscoveryJobDto> resp = controller.runJobSync(req, 60, false);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void adminController_runJobSync_failedNothingFailedNothingSucceeded_returnsOk() {
    // Cover the corner: FAILED with no failed sources at all (e.g. caller error) → 200.
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.of(USER_ID));
    DiscoveryJobDto dto = sampleJobDtoWithSources(DiscoveryJobStatus.FAILED, List.of(), List.of());
    when(discoveryService.runJobSync(eq(USER_ID), any(), any())).thenReturn(dto);

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);

    ResponseEntity<DiscoveryJobDto> resp = controller.runJobSync(req, 60, false);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void adminController_runJobSync_anonymous_throws401() {
    DiscoveryAdminController controller =
        new DiscoveryAdminController(discoveryService, currentUserResolver);
    when(currentUserResolver.currentUserId()).thenReturn(Optional.empty());

    StartDiscoveryJobRequest req =
        new StartDiscoveryJobRequest(
            DiscoveryJobTrigger.COLD_START, 5, DiscoveryTestData.sampleConstraints(), null, null);
    assertThatThrownBy(() -> controller.runJobSync(req, 60, false))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================ helpers ============================

  private DiscoveryJobDto sampleJobDto(DiscoveryJobStatus status) {
    return new DiscoveryJobDto(
        UUID.randomUUID(),
        USER_ID,
        DiscoveryJobTrigger.COLD_START,
        5,
        DiscoveryTestData.sampleConstraints(),
        List.of("src_a"),
        status,
        Instant.now(),
        null,
        null,
        0,
        0,
        0,
        0,
        List.of(),
        List.of(),
        null,
        UUID.randomUUID(),
        0L);
  }

  private DiscoveryJobDto sampleJobDtoWithSources(
      DiscoveryJobStatus status, List<String> succeeded, List<String> failed) {
    return new DiscoveryJobDto(
        UUID.randomUUID(),
        USER_ID,
        DiscoveryJobTrigger.COLD_START,
        5,
        DiscoveryTestData.sampleConstraints(),
        List.of("src_a"),
        status,
        Instant.now(),
        null,
        null,
        0,
        0,
        0,
        0,
        succeeded,
        failed,
        null,
        UUID.randomUUID(),
        0L);
  }

  private DiscoverySourceDto sampleSourceDto(String key) {
    return new DiscoverySourceDto(
        UUID.randomUUID(),
        key,
        "Sample " + key,
        DiscoverySourceKind.SITEMAP,
        "https://x.test",
        true,
        6,
        500,
        true,
        "UA",
        0,
        null,
        null,
        null,
        0L);
  }
}
