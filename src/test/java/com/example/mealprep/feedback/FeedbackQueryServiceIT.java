package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * State-contract IT for the {@code FeedbackQueryService} read methods that have no HTTP seam and
 * are therefore left uncovered by the controller ITs: {@code getByIds} (batch + cross-user filter +
 * empty-input short-circuit), {@code getRoutingDecision} (present + cross-user miss), {@code
 * listClarificationQueries} (the no-status-filter branch — the controller IT only drives the
 * status-filtered branch), and {@code getClarificationQuery} (present + cross-user miss).
 *
 * <p>Seeded directly via the repositories (no POST → no async runner racing the seed — wave-3
 * retro); methods are invoked on the autowired service so the real {@code @Transactional}/mapper
 * stack and OSIV-off lazy navigation run end-to-end against Postgres.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class FeedbackQueryServiceIT {

  @Autowired private FeedbackEntryRepository entryRepository;
  @Autowired private RoutingLogRepository routingLogRepository;
  @Autowired private ClarificationQueryRepository clarificationRepository;
  @Autowired private FeedbackQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    // Children before parents: feedback_misclassification_corrections FK-references
    // feedback_routing_log (original_routing_id) — delete it first or the routing-log delete trips
    // feedback_misclassification_corrections_original_routing_id_fkey.
    jdbcTemplate.update("DELETE FROM feedback_misclassification_corrections");
    jdbcTemplate.update("DELETE FROM feedback_clarification_queries");
    jdbcTemplate.update("DELETE FROM feedback_routing_log");
    jdbcTemplate.update("DELETE FROM feedback_entries");
  }

  private UUID seedEntryWithRecipeRoute(UUID userId, String text) {
    FeedbackEntry entry = FeedbackTestData.feedbackEntry(userId, text);
    entry.setSubmissionStatus(SubmissionStatus.ROUTED);
    entry.setClassificationAttempts(1);
    entry.getRoutingLog().clear();
    RoutingLogEntry row =
        FeedbackTestData.routingLogEntry(entry, Destination.RECIPE, RoutingStatus.APPLIED);
    entry.getRoutingLog().add(row);
    entryRepository.saveAndFlush(entry);
    return entry.getId();
  }

  @Test
  void getByIds_returnsOnlyCallersEntries_skipsMissingAndCrossUser() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID a1 = seedEntryWithRecipeRoute(alice, "a1");
    UUID a2 = seedEntryWithRecipeRoute(alice, "a2");
    UUID b1 = seedEntryWithRecipeRoute(bob, "b1");
    UUID missing = UUID.randomUUID();

    List<FeedbackEntryDto> dtos = queryService.getByIds(alice, List.of(a1, b1, missing, a2));

    // b1 (cross-user) and missing are silently omitted; a1/a2 returned.
    assertThat(dtos).extracting(FeedbackEntryDto::id).containsExactlyInAnyOrder(a1, a2);
    assertThat(dtos).allSatisfy(d -> assertThat(d.userId()).isEqualTo(alice));
    FeedbackEntryDto a1Dto = dtos.stream().filter(d -> d.id().equals(a1)).findFirst().orElseThrow();
    assertThat(a1Dto.routes()).hasSize(1);
    assertThat(a1Dto.routes().get(0).destination()).isEqualTo(Destination.RECIPE);
  }

  @Test
  void getByIds_emptyOrNullInput_shortCircuitsToEmptyList() {
    assertThat(queryService.getByIds(UUID.randomUUID(), List.of())).isEmpty();
    assertThat(queryService.getByIds(UUID.randomUUID(), null)).isEmpty();
  }

  @Test
  void getRoutingDecision_returnsRow_forOwner_andEmpty_forOtherUser() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID feedbackId = seedEntryWithRecipeRoute(alice, "salt");
    UUID routingId =
        routingLogRepository.findByFeedbackEntryIdOrderByRoutedAtAsc(feedbackId).get(0).getId();

    Optional<RoutingDecisionDto> owned = queryService.getRoutingDecision(alice, routingId);
    assertThat(owned).isPresent();
    assertThat(owned.get().id()).isEqualTo(routingId);
    assertThat(owned.get().destination()).isEqualTo(Destination.RECIPE);
    assertThat(owned.get().status()).isEqualTo(RoutingStatus.APPLIED);

    assertThat(queryService.getRoutingDecision(bob, routingId)).isEmpty();
    assertThat(queryService.getRoutingDecision(alice, UUID.randomUUID())).isEmpty();
  }

  @Test
  void listClarificationQueries_noStatusFilter_returnsAllForCaller() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();

    FeedbackEntry e1 = FeedbackTestData.feedbackEntry(alice, "ambiguous one");
    e1.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(e1);
    clarificationRepository.saveAndFlush(FeedbackTestData.clarificationQuery(e1));

    FeedbackEntry e2 = FeedbackTestData.feedbackEntry(alice, "ambiguous two");
    e2.setSubmissionStatus(SubmissionStatus.RECEIVED);
    entryRepository.saveAndFlush(e2);
    clarificationRepository.saveAndFlush(
        FeedbackTestData.answeredClarificationQuery(e2, Destination.RECIPE, "meant recipe"));

    FeedbackEntry b = FeedbackTestData.feedbackEntry(bob, "bob ambiguous");
    b.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(b);
    clarificationRepository.saveAndFlush(FeedbackTestData.clarificationQuery(b));

    // null status → the unfiltered repository branch.
    Page<ClarificationQueryDto> all =
        queryService.listClarificationQueries(alice, null, PageRequest.of(0, 20));
    assertThat(all.getTotalElements()).isEqualTo(2);
    assertThat(all.getContent())
        .extracting(ClarificationQueryDto::status)
        .containsExactlyInAnyOrder(ClarificationStatus.PENDING, ClarificationStatus.ANSWERED);

    // status-filtered branch still scoped to the caller.
    Page<ClarificationQueryDto> pendingOnly =
        queryService.listClarificationQueries(
            alice, ClarificationStatus.PENDING, PageRequest.of(0, 20));
    assertThat(pendingOnly.getTotalElements()).isEqualTo(1);
    assertThat(pendingOnly.getContent().get(0).status()).isEqualTo(ClarificationStatus.PENDING);
  }

  @Test
  void getClarificationQuery_returnsForOwner_andEmptyForOtherUser() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    FeedbackEntry e = FeedbackTestData.feedbackEntry(alice, "which one?");
    e.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(e);
    ClarificationQuery q =
        clarificationRepository.saveAndFlush(FeedbackTestData.clarificationQuery(e));

    Optional<ClarificationQueryDto> owned = queryService.getClarificationQuery(alice, q.getId());
    assertThat(owned).isPresent();
    assertThat(owned.get().id()).isEqualTo(q.getId());
    assertThat(owned.get().status()).isEqualTo(ClarificationStatus.PENDING);
    assertThat(owned.get().options()).isNotEmpty();

    assertThat(queryService.getClarificationQuery(bob, q.getId())).isEmpty();
    assertThat(queryService.getClarificationQuery(alice, UUID.randomUUID())).isEmpty();
  }
}
