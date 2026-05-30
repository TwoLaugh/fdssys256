package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.dto.DecisionLogWriteRequest;
import com.example.mealprep.core.audit.api.mapper.DecisionLogMapper;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import com.example.mealprep.core.audit.domain.repository.DecisionLogRepository;
import com.example.mealprep.core.audit.domain.service.internal.DecisionLogServiceImpl;
import com.example.mealprep.core.audit.domain.service.internal.DecisionLogTokenBudgetGuard;
import com.example.mealprep.core.exception.DecisionLogNotFoundException;
import com.example.mealprep.core.exception.DecisionLogPayloadOversizedException;
import com.example.mealprep.core.testdata.DecisionLogTestData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionLogServiceImplTest {

  @Mock private DecisionLogRepository repository;
  @Mock private DecisionLogMapper mapper;
  @Mock private DecisionLogTokenBudgetGuard tokenBudgetGuard;
  @InjectMocks private DecisionLogServiceImpl service;

  // ---------------- write ----------------

  @Test
  void write_generatesDecisionId_andPersistsEntityWithRequestFields() {
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withTriggeredBy("the-trigger").build();

    UUID returned = service.write(req);

    assertThat(returned).isNotNull();

    ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
    verify(repository).save(captor.capture());
    DecisionLog saved = captor.getValue();

    assertThat(saved.getDecisionId()).isEqualTo(returned);
    assertThat(saved.getTraceId()).isEqualTo(req.traceId());
    assertThat(saved.getScopeKind()).isEqualTo(req.scopeKind());
    assertThat(saved.getScopeId()).isEqualTo(req.scopeId());
    assertThat(saved.getTriggeredBy()).isEqualTo("the-trigger");
    assertThat(saved.getInputs()).isEqualTo(req.inputs());
    assertThat(saved.getIteration()).isEqualTo(req.iteration());
  }

  @Test
  void write_generatesDistinctIds_acrossCalls_whenNoCallerIdSupplied() {
    DecisionLogWriteRequest req = DecisionLogTestData.builder().build();

    UUID id1 = service.write(req);
    UUID id2 = service.write(req);

    assertThat(id1).isNotEqualTo(id2);
  }

  @Test
  void write_usesCallerSuppliedDecisionId_whenNotYetPersisted() {
    UUID callerId = UUID.randomUUID();
    DecisionLogWriteRequest req = DecisionLogTestData.builder().withDecisionId(callerId).build();
    when(repository.existsById(callerId)).thenReturn(false);

    UUID returned = service.write(req);

    assertThat(returned).isEqualTo(callerId);
    ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getDecisionId()).isEqualTo(callerId);
  }

  @Test
  void write_isIdempotent_returnsExistingId_withoutSecondInsert_whenDecisionIdAlreadyExists() {
    UUID callerId = UUID.randomUUID();
    DecisionLogWriteRequest req = DecisionLogTestData.builder().withDecisionId(callerId).build();
    when(repository.existsById(callerId)).thenReturn(true);

    UUID returned = service.write(req);

    assertThat(returned).isEqualTo(callerId);
    verify(repository, never()).save(any());
  }

  @Test
  void write_throwsNotFound_whenParentDoesNotExist() {
    UUID parent = UUID.randomUUID();
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withParentDecisionId(parent).build();
    when(repository.existsById(parent)).thenReturn(false);

    assertThatThrownBy(() -> service.write(req)).isInstanceOf(DecisionLogNotFoundException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void write_persists_whenParentExists() {
    UUID parent = UUID.randomUUID();
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder().withParentDecisionId(parent).build();
    when(repository.existsById(parent)).thenReturn(true);

    service.write(req);

    verify(repository).save(any());
  }

  @Test
  void write_throwsOversized_whenGuardRejects_andDoesNotPersist() {
    DecisionLogWriteRequest req = DecisionLogTestData.builder().build();
    doThrow(new DecisionLogPayloadOversizedException(70_000L, 65_536L))
        .when(tokenBudgetGuard)
        .assertWithinBudget(req);

    assertThatThrownBy(() -> service.write(req))
        .isInstanceOf(DecisionLogPayloadOversizedException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void write_passesNullableFields_through() {
    UUID parent = UUID.randomUUID();
    DecisionLogWriteRequest req =
        DecisionLogTestData.builder()
            .withParentDecisionId(parent)
            .withActorUserId(UUID.randomUUID())
            .withReasoning("user picked option B")
            .withDurationMs(1234)
            .build();
    when(repository.existsById(parent)).thenReturn(true);

    service.write(req);

    ArgumentCaptor<DecisionLog> captor = ArgumentCaptor.forClass(DecisionLog.class);
    verify(repository).save(captor.capture());
    DecisionLog saved = captor.getValue();

    assertThat(saved.getParentDecisionId()).isEqualTo(req.parentDecisionId());
    assertThat(saved.getActorUserId()).isEqualTo(req.actorUserId());
    assertThat(saved.getReasoning()).isEqualTo("user picked option B");
    assertThat(saved.getDurationMs()).isEqualTo(1234);
  }

  @Test
  void write_rejectsNullRequest() {
    assertThatThrownBy(() -> service.write(null)).isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).save(any());
  }

  // ---------------- getById ----------------

  @Test
  void getById_mapsRepoResult_whenPresent() {
    UUID id = UUID.randomUUID();
    DecisionLog entity = sampleEntity(id);
    DecisionLogDto dto = sampleDto(id);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(mapper.toDto(entity)).thenReturn(dto);

    Optional<DecisionLogDto> result = service.getById(id);

    assertThat(result).contains(dto);
  }

  @Test
  void getById_returnsEmpty_whenAbsent() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    Optional<DecisionLogDto> result = service.getById(id);

    assertThat(result).isEmpty();
  }

  // ---------------- getByTraceId ----------------

  @Test
  void getByTraceId_mapsRepoList_inOrder() {
    UUID traceId = UUID.randomUUID();
    List<DecisionLog> entities =
        List.of(sampleEntity(UUID.randomUUID()), sampleEntity(UUID.randomUUID()));
    List<DecisionLogDto> dtos = List.of(sampleDto(UUID.randomUUID()), sampleDto(UUID.randomUUID()));
    when(repository.findByTraceIdOrderByCreatedAtAsc(traceId)).thenReturn(entities);
    when(mapper.toDtos(entities)).thenReturn(dtos);

    List<DecisionLogDto> result = service.getByTraceId(traceId);

    assertThat(result).isEqualTo(dtos);
  }

  @Test
  void getByTraceId_returnsEmptyList_whenNoMatches() {
    UUID traceId = UUID.randomUUID();
    when(repository.findByTraceIdOrderByCreatedAtAsc(traceId)).thenReturn(List.of());
    when(mapper.toDtos(List.of())).thenReturn(List.of());

    List<DecisionLogDto> result = service.getByTraceId(traceId);

    assertThat(result).isEmpty();
  }

  // ---------------- getByScope ----------------

  @Test
  void getByScope_mapsRepoList_forScopeKindAndId() {
    String scopeKind = "plan-week";
    UUID scopeId = UUID.randomUUID();
    List<DecisionLog> entities =
        List.of(sampleEntity(UUID.randomUUID()), sampleEntity(UUID.randomUUID()));
    List<DecisionLogDto> dtos = List.of(sampleDto(UUID.randomUUID()), sampleDto(UUID.randomUUID()));
    when(repository.findByScopeKindAndScopeIdOrderByCreatedAtAsc(scopeKind, scopeId))
        .thenReturn(entities);
    when(mapper.toDtos(entities)).thenReturn(dtos);

    List<DecisionLogDto> result = service.getByScope(scopeKind, scopeId);

    assertThat(result).isEqualTo(dtos);
    verify(repository).findByScopeKindAndScopeIdOrderByCreatedAtAsc(scopeKind, scopeId);
  }

  @Test
  void getByScope_returnsEmptyList_whenNoMatches() {
    String scopeKind = "recipe";
    UUID scopeId = UUID.randomUUID();
    when(repository.findByScopeKindAndScopeIdOrderByCreatedAtAsc(scopeKind, scopeId))
        .thenReturn(List.of());
    when(mapper.toDtos(List.of())).thenReturn(List.of());

    List<DecisionLogDto> result = service.getByScope(scopeKind, scopeId);

    assertThat(result).isEmpty();
  }

  // ---------------- getAncestry ----------------

  @Test
  void getAncestry_clampsMaxDepth_toAtLeastOne() {
    UUID id = UUID.randomUUID();
    when(repository.findAncestry(id, 1)).thenReturn(List.of());
    when(mapper.toDtos(List.of())).thenReturn(List.of());

    AncestryResponse response = service.getAncestry(id, 0);

    assertThat(response.ancestors()).isEmpty();
    assertThat(response.cycleDetected()).isFalse();
    verify(repository).findAncestry(id, 1);
  }

  @Test
  void getAncestry_clampsMaxDepth_toCap() {
    UUID id = UUID.randomUUID();
    when(repository.findAncestry(id, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP))
        .thenReturn(List.of());
    when(mapper.toDtos(List.of())).thenReturn(List.of());

    service.getAncestry(id, 9999);

    verify(repository).findAncestry(id, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP);
  }

  @Test
  void getAncestry_flagsCycle_whenWalkHitsCap() {
    UUID id = UUID.randomUUID();
    int cap = DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP;
    List<DecisionLog> entities = new ArrayList<>();
    List<DecisionLogDto> dtos = new ArrayList<>();
    for (int i = 0; i < cap; i++) {
      entities.add(sampleEntity(UUID.randomUUID()));
      dtos.add(sampleDto(UUID.randomUUID()));
    }
    when(repository.findAncestry(id, cap)).thenReturn(entities);
    when(mapper.toDtos(entities)).thenReturn(dtos);

    AncestryResponse response = service.getAncestry(id, cap);

    assertThat(response.ancestors()).hasSize(cap);
    assertThat(response.cycleDetected()).isTrue();
  }

  @Test
  void getAncestry_doesNotFlagCycle_whenWalkUnderCap() {
    UUID id = UUID.randomUUID();
    List<DecisionLog> entities = List.of(sampleEntity(UUID.randomUUID()));
    List<DecisionLogDto> dtos = List.of(sampleDto(UUID.randomUUID()));
    when(repository.findAncestry(id, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP))
        .thenReturn(entities);
    when(mapper.toDtos(entities)).thenReturn(dtos);

    AncestryResponse response = service.getAncestry(id, DecisionLogServiceImpl.ANCESTRY_DEPTH_CAP);

    assertThat(response.ancestors()).hasSize(1);
    assertThat(response.cycleDetected()).isFalse();
  }

  // ---------------- helpers ----------------

  private DecisionLog sampleEntity(UUID id) {
    return new DecisionLog(
        id,
        UUID.randomUUID(),
        null,
        "scope",
        UUID.randomUUID(),
        DecisionLogScale.OTHER,
        "test",
        null,
        DecisionLogTestData.emptyJson(),
        null,
        null,
        null,
        null,
        0,
        null);
  }

  private DecisionLogDto sampleDto(UUID id) {
    return new DecisionLogDto(
        id,
        UUID.randomUUID(),
        null,
        "scope",
        UUID.randomUUID(),
        DecisionLogScale.OTHER,
        "test",
        null,
        DecisionLogTestData.emptyJson(),
        null,
        null,
        null,
        null,
        0,
        null,
        Instant.now());
  }
}
