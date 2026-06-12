package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.RoutingStatus;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.repository.MisclassificationCorrectionRepository;
import com.example.mealprep.feedback.domain.repository.RoutingLogRepository;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
  @Autowired private MisclassificationCorrectionRepository correctionRepository;
  @Autowired private FeedbackQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

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

  /**
   * feedback-7: the batched {@code getByIds} resolves each DTO's {@code
   * pendingClarificationQueryId} through one batched clarification lookup (not a per-entry
   * round-trip). Seed one entry WITH a PENDING clarification and one WITHOUT, then assert the batch
   * returns the right id on each.
   */
  @Test
  void getByIds_populatesPendingClarificationId_perEntry_viaBatchedLookup() {
    UUID alice = UUID.randomUUID();

    FeedbackEntry withPending = FeedbackTestData.feedbackEntry(alice, "which one?");
    withPending.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    entryRepository.saveAndFlush(withPending);
    ClarificationQuery pending =
        clarificationRepository.saveAndFlush(FeedbackTestData.clarificationQuery(withPending));

    UUID withoutPending = seedEntryWithRecipeRoute(alice, "routed");

    List<FeedbackEntryDto> dtos =
        queryService.getByIds(alice, List.of(withPending.getId(), withoutPending));

    FeedbackEntryDto pendingDto =
        dtos.stream().filter(d -> d.id().equals(withPending.getId())).findFirst().orElseThrow();
    assertThat(pendingDto.pendingClarificationQueryId()).isEqualTo(pending.getId());
    FeedbackEntryDto routedDto =
        dtos.stream().filter(d -> d.id().equals(withoutPending)).findFirst().orElseThrow();
    assertThat(routedDto.pendingClarificationQueryId()).isNull();
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
    // Each card carries its originating entry's text as the excerpt (frontend-gaps ticket).
    assertThat(all.getContent())
        .extracting(ClarificationQueryDto::textExcerpt)
        .containsExactlyInAnyOrder("ambiguous one", "ambiguous two");

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
    assertThat(owned.get().textExcerpt()).isEqualTo("which one?");

    assertThat(queryService.getClarificationQuery(bob, q.getId())).isEmpty();
    assertThat(queryService.getClarificationQuery(alice, UUID.randomUUID())).isEmpty();
  }

  /**
   * frontend-gaps (feedback-clarification-text-excerpt): the {@code textExcerpt} denormalisation
   * must ride the {@code @EntityGraph} join — the JDBC statement count for a 3-row page must equal
   * the 1-row page's (a per-row parent-entry fetch would scale with the row count). Asserted for
   * both the clarifications inbox and the corrections log via Hibernate statistics, mirroring
   * {@code RecipeListSearchIT}'s no-N+1 pattern.
   */
  @Test
  void listReads_carryTextExcerpt_withoutPerRowParentEntryFetches() {
    UUID alice = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      FeedbackEntry pending = FeedbackTestData.feedbackEntry(alice, "clarify me " + i);
      pending.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
      entryRepository.saveAndFlush(pending);
      clarificationRepository.saveAndFlush(FeedbackTestData.clarificationQuery(pending));

      UUID routedEntryId = seedEntryWithRecipeRoute(alice, "correct me " + i);
      UUID routingId =
          routingLogRepository
              .findByFeedbackEntryIdOrderByRoutedAtAsc(routedEntryId)
              .get(0)
              .getId();
      FeedbackEntry routed = entryRepository.findById(routedEntryId).orElseThrow();
      correctionRepository.saveAndFlush(
          FeedbackTestData.misclassificationCorrection(routed, routingId, alice));
    }

    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    stats.clear();
    Page<ClarificationQueryDto> oneClarification =
        queryService.listClarificationQueries(alice, null, PageRequest.of(0, 1));
    long clarificationOneRowStatements = stats.getPrepareStatementCount();
    stats.clear();
    Page<ClarificationQueryDto> threeClarifications =
        queryService.listClarificationQueries(alice, null, PageRequest.of(0, 3));
    long clarificationThreeRowStatements = stats.getPrepareStatementCount();

    assertThat(oneClarification.getContent()).hasSize(1);
    assertThat(threeClarifications.getContent()).hasSize(3);
    assertThat(threeClarifications.getContent())
        .extracting(ClarificationQueryDto::textExcerpt)
        .containsExactlyInAnyOrder("clarify me 0", "clarify me 1", "clarify me 2");
    assertThat(clarificationThreeRowStatements)
        .as("JDBC statements for a 3-row clarification page vs 1-row (N+1 would scale per row)")
        .isEqualTo(clarificationOneRowStatements);

    stats.clear();
    Page<MisclassificationCorrectionDto> oneCorrection =
        queryService.listCorrections(alice, PageRequest.of(0, 1));
    long correctionOneRowStatements = stats.getPrepareStatementCount();
    stats.clear();
    Page<MisclassificationCorrectionDto> threeCorrections =
        queryService.listCorrections(alice, PageRequest.of(0, 3));
    long correctionThreeRowStatements = stats.getPrepareStatementCount();

    assertThat(oneCorrection.getContent()).hasSize(1);
    assertThat(threeCorrections.getContent()).hasSize(3);
    assertThat(threeCorrections.getContent())
        .extracting(MisclassificationCorrectionDto::textExcerpt)
        .containsExactlyInAnyOrder("correct me 0", "correct me 1", "correct me 2");
    assertThat(correctionThreeRowStatements)
        .as("JDBC statements for a 3-row corrections page vs 1-row (N+1 would scale per row)")
        .isEqualTo(correctionOneRowStatements);
  }
}
