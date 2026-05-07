package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.domain.repository.DecisionLogRepository;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.example.mealprep.core.audit.domain.service.DecisionLogService;
import com.example.mealprep.core.audit.domain.service.internal.DecisionLogServiceImpl;
import com.example.mealprep.core.testdata.DecisionLogTestData;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Testcontainers-backed integration test for the decision-log service.
 *
 * <p>Verifies: migration runs against real Postgres; JSONB columns round-trip; the
 * {@code @Transactional(REQUIRES_NEW)} write survives caller transaction rollback; trace lookup
 * returns rows in created_at order; recursive ancestry CTE walks the parent chain.
 *
 * <p>Requires Docker; uses the {@code tc:postgresql:16-alpine} JDBC URL convention from {@code
 * application-test.properties}.
 */
@SpringBootTest
class DecisionLogServiceIT {

  @Autowired private DecisionLogService writeService;
  @Autowired private DecisionLogQueryService queryService;
  @Autowired private DecisionLogRepository repository;
  @Autowired private PlatformTransactionManager txManager;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void write_thenGetById_roundTripsAllFields_includingJsonb() {
    var inputs = DecisionLogTestData.jsonObject("scoreThreshold", "0.7");
    var candidates = DecisionLogTestData.jsonObject("count", "5");
    var chosen = DecisionLogTestData.jsonObject("index", "2");
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder()
            .withScope("plan-week", UUID.randomUUID())
            .withScale(DecisionLogScale.WEEK)
            .withTriggeredBy("user-initiated")
            .withActorUserId(UUID.randomUUID())
            .withInputs(inputs)
            .withCandidates(candidates)
            .withChosen(chosen)
            .withReasoning("variety beat cost on this run")
            .withIteration(3)
            .withDurationMs(742)
            .build();

    UUID decisionId = writeService.write(req);

    Optional<DecisionLogDto> read = queryService.getById(decisionId);
    assertThat(read).isPresent();
    DecisionLogDto dto = read.orElseThrow();
    assertThat(dto.decisionId()).isEqualTo(decisionId);
    assertThat(dto.traceId()).isEqualTo(req.traceId());
    assertThat(dto.scopeKind()).isEqualTo("plan-week");
    assertThat(dto.scale()).isEqualTo(DecisionLogScale.WEEK);
    assertThat(dto.triggeredBy()).isEqualTo("user-initiated");
    assertThat(dto.actorUserId()).isEqualTo(req.actorUserId());
    assertThat(dto.inputs()).isEqualTo(inputs);
    assertThat(dto.candidates()).isEqualTo(candidates);
    assertThat(dto.chosen()).isEqualTo(chosen);
    assertThat(dto.reasoning()).isEqualTo("variety beat cost on this run");
    assertThat(dto.iteration()).isEqualTo(3);
    assertThat(dto.durationMs()).isEqualTo(742);
    assertThat(dto.createdAt()).isNotNull();
  }

  @Test
  void write_inRequiresNewTransaction_survivesCallerRollback() {
    UUID traceId = UUID.randomUUID();
    DecisionLogWriteRequest req = DecisionLogTestData.builder().withTraceId(traceId).build();

    // Caller starts a tx, calls write (which runs in REQUIRES_NEW), then rolls back.
    // The decision-log row must persist because write committed in its own tx.
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(Propagation.REQUIRED.value());

    UUID decisionId =
        template.execute(
            status -> {
              UUID id = writeService.write(req);
              status.setRollbackOnly();
              return id;
            });

    // Even though caller rolled back, the row exists.
    assertThat(repository.findById(decisionId)).isPresent();
  }

  @Test
  void getByTraceId_returnsRows_inCreatedAtOrder() {
    UUID traceId = UUID.randomUUID();
    UUID first = writeService.write(DecisionLogTestData.builder().withTraceId(traceId).build());
    UUID second = writeService.write(DecisionLogTestData.builder().withTraceId(traceId).build());
    UUID third = writeService.write(DecisionLogTestData.builder().withTraceId(traceId).build());

    List<DecisionLogDto> trace = queryService.getByTraceId(traceId);

    assertThat(trace).extracting(DecisionLogDto::decisionId).containsExactly(first, second, third);
  }

  @Test
  void getByTraceId_returnsEmptyList_whenTraceUnknown() {
    List<DecisionLogDto> trace = queryService.getByTraceId(UUID.randomUUID());
    assertThat(trace).isEmpty();
  }

  @Test
  void getById_returnsEmpty_whenIdUnknown() {
    Optional<DecisionLogDto> result = queryService.getById(UUID.randomUUID());
    assertThat(result).isEmpty();
  }

  @Test
  void getAncestry_walksParentChain_rootFirst() {
    UUID traceId = UUID.randomUUID();
    UUID root =
        writeService.write(
            DecisionLogTestData.builder().withTraceId(traceId).withIteration(0).build());
    UUID middle =
        writeService.write(
            DecisionLogTestData.builder()
                .withTraceId(traceId)
                .withParentDecisionId(root)
                .withIteration(1)
                .build());
    UUID leaf =
        writeService.write(
            DecisionLogTestData.builder()
                .withTraceId(traceId)
                .withParentDecisionId(middle)
                .withIteration(2)
                .build());

    AncestryResponse response =
        queryService.getAncestry(leaf, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP);

    assertThat(response.ancestors())
        .extracting(DecisionLogDto::decisionId)
        .containsExactly(root, middle);
    assertThat(response.cycleDetected()).isFalse();
  }

  @Test
  void getAncestry_returnsEmpty_whenLeafIsRoot() {
    UUID root =
        writeService.write(DecisionLogTestData.builder().withParentDecisionId(null).build());

    AncestryResponse response =
        queryService.getAncestry(root, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP);

    assertThat(response.ancestors()).isEmpty();
    assertThat(response.cycleDetected()).isFalse();
  }

  @Test
  void getAncestry_returnsEmpty_whenLeafUnknown() {
    AncestryResponse response =
        queryService.getAncestry(UUID.randomUUID(), DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP);

    assertThat(response.ancestors()).isEmpty();
    assertThat(response.cycleDetected()).isFalse();
  }
}
