package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.core.audit.api.controller.AdminDecisionLogController;
import com.example.mealprep.core.audit.api.dto.AncestryResponse;
import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.domain.service.DecisionLogQueryService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure-unit invocation of {@link AdminDecisionLogController}. The existing {@code
 * DecisionLogControllerIT} uses {@code @WebMvcTest} so Pitest (unit-only) never enters the
 * controller body — every mutation on the controller was therefore {@code NO_COVERAGE}. This test
 * exercises each method directly with a mocked query service, killing the four survivors:
 *
 * <ul>
 *   <li>line 50: {@code getById} {@code NullReturnVals}
 *   <li>line 55: {@code lambda$getById$0} {@code NullReturnVals} — the orElseThrow factory
 *   <li>line 64: {@code getByTraceId} {@code EmptyObjectReturnVals}
 *   <li>line 79: {@code getAncestry} {@code NullReturnVals}
 * </ul>
 *
 * <p>No Spring context, no MockMvc, no @PreAuthorize gating — the unit test exists for mutation
 * coverage of the plain-Java code paths. End-to-end HTTP behaviour stays in {@code
 * DecisionLogControllerIT}.
 */
@ExtendWith(MockitoExtension.class)
class AdminDecisionLogControllerUnitTest {

  @Mock private DecisionLogQueryService queryService;
  @InjectMocks private AdminDecisionLogController controller;

  private static DecisionLogDto sampleDto(UUID decisionId) {
    return new DecisionLogDto(
        decisionId,
        UUID.randomUUID(),
        null,
        "plan-week",
        UUID.randomUUID(),
        DecisionLogScale.WEEK,
        "user-initiated",
        null,
        JsonNodeFactory.instance.objectNode(),
        null,
        null,
        null,
        null,
        3,
        742,
        Instant.parse("2026-05-07T10:00:00Z"));
  }

  // ---------------- getById ----------------

  @Test
  void getById_returnsOkResponseEntity_whenServiceReturnsValue() {
    // Kills AdminDecisionLogController.java:50 NullReturnVals — direct return mutated to null.
    UUID decisionId = UUID.randomUUID();
    DecisionLogDto dto = sampleDto(decisionId);
    when(queryService.getById(decisionId)).thenReturn(Optional.of(dto));

    ResponseEntity<DecisionLogDto> response = controller.getById(decisionId);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(dto);
    verify(queryService).getById(decisionId);
  }

  @Test
  void getById_throwsNotFound_whenServiceReturnsEmpty() {
    // Kills AdminDecisionLogController.java:55 NullReturnVals — the orElseThrow factory lambda;
    // if it returned null instead of the exception instance, Optional.orElseThrow would throw NPE,
    // not a 404 — assert on the specific exception type + status to detect either substitution.
    UUID decisionId = UUID.randomUUID();
    when(queryService.getById(decisionId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getById(decisionId))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(ex.getReason()).contains(decisionId.toString());
            });
  }

  // ---------------- getByTraceId ----------------

  @Test
  void getByTraceId_returnsServiceList_inOrder() {
    // Kills AdminDecisionLogController.java:64 EmptyObjectReturnVals — replaces the returned list
    // with Collections.emptyList(). Asserting size + element identity catches the substitution.
    UUID traceId = UUID.randomUUID();
    DecisionLogDto a = sampleDto(UUID.randomUUID());
    DecisionLogDto b = sampleDto(UUID.randomUUID());
    when(queryService.getByTraceId(traceId)).thenReturn(List.of(a, b));

    List<DecisionLogDto> result = controller.getByTraceId(traceId);

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isSameAs(a);
    assertThat(result.get(1)).isSameAs(b);
    verify(queryService).getByTraceId(traceId);
  }

  @Test
  void getByTraceId_passes_traceId_through_unchanged() {
    // Defensive against an ArgumentMatcher swap in a future refactor: the trace UUID arriving at
    // the service must be the exact one given to the controller.
    UUID traceId = UUID.fromString("11111111-2222-3333-4444-555555555555");
    when(queryService.getByTraceId(traceId)).thenReturn(List.of());

    controller.getByTraceId(traceId);

    verify(queryService).getByTraceId(traceId);
  }

  // ---------------- getAncestry ----------------

  @Test
  void getAncestry_returnsServiceResponse_unchanged() {
    // Kills AdminDecisionLogController.java:79 NullReturnVals — would replace the AncestryResponse
    // return with null. Assert non-null + same-instance to fail under any substitution.
    UUID decisionId = UUID.randomUUID();
    AncestryResponse expected = new AncestryResponse(List.of(sampleDto(UUID.randomUUID())), false);
    when(queryService.getAncestry(decisionId, 16)).thenReturn(expected);

    AncestryResponse actual = controller.getAncestry(decisionId, 16);

    assertThat(actual).isSameAs(expected);
    verify(queryService).getAncestry(decisionId, 16);
  }

  @Test
  void getAncestry_passes_maxDepth_through_unchanged() {
    // Pins the maxDepth argument so a +1/-1 mutation on the controller's param wiring would fail.
    // The @Min/@Max are AOP-enforced; here we only validate the in-method pass-through.
    UUID decisionId = UUID.randomUUID();
    AncestryResponse stub = new AncestryResponse(List.of(), true);
    when(queryService.getAncestry(decisionId, 4)).thenReturn(stub);

    AncestryResponse actual = controller.getAncestry(decisionId, 4);

    assertThat(actual).isSameAs(stub);
    assertThat(actual.cycleDetected()).isTrue();
    verify(queryService).getAncestry(decisionId, 4);
  }
}
