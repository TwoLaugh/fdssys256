package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.TaskType;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.feedback.api.controller.ClarificationQueryController;
import com.example.mealprep.feedback.api.controller.FeedbackController;
import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.ClarificationOptionDto;
import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.Screen;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.api.dto.UiContextDto;
import com.example.mealprep.feedback.api.mapper.ClarificationQueryMapper;
import com.example.mealprep.feedback.api.mapper.FeedbackEntryMapper;
import com.example.mealprep.feedback.api.mapper.RoutingLogMapper;
import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.MisclassificationCorrection;
import com.example.mealprep.feedback.domain.entity.RoutingLogEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationContext;
import com.example.mealprep.feedback.domain.service.internal.FeedbackClassificationTask;
import com.example.mealprep.feedback.domain.service.internal.ToolDefinitions;
import com.example.mealprep.feedback.exception.ClarificationQueryAlreadyAnsweredException;
import com.example.mealprep.feedback.exception.ClarificationQueryExpiredException;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import com.example.mealprep.feedback.exception.RoutingDecisionNotFoundException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import com.example.mealprep.feedback.validation.ValidDestination;
import com.example.mealprep.feedback.validation.ValidDestinationValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Targeted unit tests that kill SURVIVED + NO_COVERAGE mutants identified by Pitest on the feedback
 * module. Pure unit — no Spring context, no Testcontainers, no DB. Each test asserts a specific
 * behaviour whose break-on-mutation is documented in-line.
 */
class FeedbackMutationKillsTest {

  // ============================================================
  // ToolDefinitions — classifyFeedback() NO_COVERAGE NullReturnVals
  // Plus content assertions to keep future @JsonSchema mutations honest.
  // ============================================================
  @Test
  void toolDefinitions_classifyFeedback_returnsNonNullToolWithFixedName() {
    ToolDefinition def = ToolDefinitions.classifyFeedback();
    // Killing NullReturnValsMutator on classifyFeedback().
    assertThat(def).isNotNull();
    assertThat(def.name()).isEqualTo(ToolDefinitions.CLASSIFY_FEEDBACK_TOOL_NAME);
    assertThat(def.name()).isEqualTo("classify_feedback");
    assertThat(def.description()).contains("Classify free-text feedback");
  }

  @Test
  void toolDefinitions_schemaShape_matchesClassificationResultRecord() {
    ToolDefinition def = ToolDefinitions.classifyFeedback();
    JsonNode schema = def.inputSchema();
    assertThat(schema.get("type").asText()).isEqualTo("object");
    JsonNode properties = schema.get("properties");
    assertThat(properties).isNotNull();
    // Top-level required: classifications + overallConfidence.
    List<String> requiredTop = new ArrayList<>();
    schema.get("required").forEach(n -> requiredTop.add(n.asText()));
    assertThat(requiredTop).containsExactlyInAnyOrder("classifications", "overallConfidence");

    // classifications is an array bounded [0..4].
    JsonNode classifications = properties.get("classifications");
    assertThat(classifications.get("type").asText()).isEqualTo("array");
    assertThat(classifications.get("minItems").asInt()).isEqualTo(0);
    assertThat(classifications.get("maxItems").asInt()).isEqualTo(4);

    // item.destination is an enum of the four Destination names.
    JsonNode itemProps = classifications.get("items").get("properties");
    JsonNode destEnum = itemProps.get("destination").get("enum");
    List<String> enumValues = new ArrayList<>();
    destEnum.forEach(n -> enumValues.add(n.asText()));
    assertThat(enumValues)
        .containsExactlyInAnyOrder("RECIPE", "PREFERENCE", "NUTRITION", "PROVISIONS");

    // confidence in [0,1].
    JsonNode confidence = itemProps.get("confidence");
    assertThat(confidence.get("type").asText()).isEqualTo("number");
    assertThat(confidence.get("minimum").asDouble()).isEqualTo(0.0);
    assertThat(confidence.get("maximum").asDouble()).isEqualTo(1.0);

    // extractedFeedback min length 1.
    assertThat(itemProps.get("extractedFeedback").get("minLength").asInt()).isEqualTo(1);

    // item required list pins all four fields.
    List<String> itemRequired = new ArrayList<>();
    classifications.get("items").get("required").forEach(n -> itemRequired.add(n.asText()));
    assertThat(itemRequired)
        .containsExactlyInAnyOrder(
            "destination", "confidence", "extractedFeedback", "structuredPayload");

    // overallConfidence in [0,1].
    JsonNode overall = properties.get("overallConfidence");
    assertThat(overall.get("minimum").asDouble()).isEqualTo(0.0);
    assertThat(overall.get("maximum").asDouble()).isEqualTo(1.0);
  }

  @Test
  void toolDefinitions_isStable_returnsSameInstanceAcrossCalls() {
    // The build() factory is invoked once at class load; subsequent calls return the cached
    // singleton — killing any future "rebuild on every call" mutation.
    assertThat(ToolDefinitions.classifyFeedback()).isSameAs(ToolDefinitions.classifyFeedback());
  }

  // ============================================================
  // FeedbackClassificationTask — tier(), prompt(), variables(), tools() NO_COVERAGE
  // ============================================================
  @Test
  void feedbackClassificationTask_exposesEveryAiTaskField() {
    UUID userId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            userId,
            traceId,
            "make my lasagne lighter",
            new UiContextDto(Screen.RECIPE_DETAIL, UUID.randomUUID(), 1, null, null, null),
            Optional.empty(),
            Optional.empty(),
            1);
    FeedbackClassificationTask task = new FeedbackClassificationTask(ctx);

    // tier() — kills NullReturnVals.
    assertThat(task.tier()).isEqualTo(ModelTier.CHEAP);
    // type() — pin the constant.
    assertThat(task.type()).isEqualTo(TaskType.FEEDBACK_CLASSIFICATION);
    // prompt() — name + version pinned.
    assertThat(task.prompt()).isNotNull();
    assertThat(task.prompt().name()).isEqualTo(FeedbackClassificationTask.PROMPT_NAME);
    assertThat(task.prompt().name()).isEqualTo("feedback/classify-feedback");
    assertThat(task.prompt().version()).isEqualTo(FeedbackClassificationTask.PROMPT_VERSION);
    assertThat(task.prompt().version()).isEqualTo(1);
    // variables() — kills EmptyObjectReturnVals (replaces map with empty).
    Map<String, Object> vars = task.variables();
    assertThat(vars).isNotEmpty();
    assertThat(vars).containsKey("feedback_text").containsKey("screen_context");
    // outputType — pinned.
    assertThat(task.outputType())
        .isEqualTo(com.example.mealprep.feedback.api.dto.ClassificationResult.class);
    // tools() — kills EmptyObjectReturnVals (Optional.empty) — Optional present + one tool.
    Optional<List<ToolDefinition>> tools = task.tools();
    assertThat(tools).isPresent();
    assertThat(tools.get()).hasSize(1);
    assertThat(tools.get().get(0).name()).isEqualTo("classify_feedback");
    // userId / traceId propagate.
    assertThat(task.userId()).contains(userId);
    assertThat(task.traceId()).contains(traceId);
  }

  @Test
  void feedbackClassificationTask_nullContext_throws() {
    assertThatThrownBy(() -> new FeedbackClassificationTask(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ============================================================
  // FeedbackClassificationContext — currentMealContext branches
  // Line 73 NO_COVERAGE: uiContext == null → null
  // Line 82 SURVIVED: returns the populated map — body content needs assertion
  // ============================================================
  @Test
  void classificationContext_renderer_nullUiContext_screenContextIsGeneral_mealContextIsNull() {
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            null, // null uiContext — covers L73
            Optional.empty(),
            Optional.empty(),
            1);
    Map<String, Object> map = ctx.toRendererMap();
    assertThat(map.get("screen_context")).isEqualTo("general");
    assertThat(map.get("current_meal_context")).isNull();
  }

  @Test
  void classificationContext_renderer_onlyRecipeId_populatesMealMapPreservingValues() {
    UUID recipeId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            new UiContextDto(Screen.RECIPE_DETAIL, recipeId, 1, null, null, null),
            Optional.empty(),
            Optional.empty(),
            1);
    @SuppressWarnings("unchecked")
    Map<String, Object> meal =
        (Map<String, Object>) ctx.toRendererMap().get("current_meal_context");
    // EmptyObjectReturnValsMutator on L82 replaces with emptyMap — these asserts kill it.
    assertThat(meal).isNotNull().isNotEmpty();
    assertThat(meal).containsKey("recipeId").containsKey("mealSlotId").containsKey("eatenAt");
    assertThat(meal.get("recipeId")).isEqualTo(recipeId);
    assertThat(meal.get("mealSlotId")).isNull();
    assertThat(meal.get("eatenAt")).isNull();
  }

  @Test
  void classificationContext_renderer_onlyMealSlotId_populatesMealMap() {
    UUID mealSlotId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    FeedbackClassificationContext ctx =
        new FeedbackClassificationContext(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "x",
            new UiContextDto(
                Screen.PLAN_MEAL_DETAIL, null, null, mealSlotId, UUID.randomUUID(), null),
            Optional.empty(),
            Optional.empty(),
            1);
    @SuppressWarnings("unchecked")
    Map<String, Object> meal =
        (Map<String, Object>) ctx.toRendererMap().get("current_meal_context");
    assertThat(meal).isNotNull();
    assertThat(meal.get("mealSlotId")).isEqualTo(mealSlotId);
    assertThat(meal.get("recipeId")).isNull();
  }

  // ============================================================
  // Exception classes — feedbackEntryId(), queryId(), routingId(), feedbackEntryId() NO_COVERAGE
  // Each NullReturnVals would replace the accessor with null; assert the field round-trips.
  // ============================================================
  @Test
  void feedbackEntryNotFound_exception_carriesId() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    FeedbackEntryNotFoundException ex = new FeedbackEntryNotFoundException(id);
    assertThat(ex.feedbackEntryId()).isEqualTo(id);
    assertThat(ex.getMessage()).contains(id.toString());
  }

  @Test
  void routingDecisionNotFound_exception_carriesId() {
    UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
    RoutingDecisionNotFoundException ex = new RoutingDecisionNotFoundException(id);
    assertThat(ex.routingId()).isEqualTo(id);
    assertThat(ex.getMessage()).contains(id.toString());
  }

  @Test
  void clarificationQueryNotFound_exception_carriesId() {
    UUID id = UUID.fromString("33333333-3333-3333-3333-333333333333");
    ClarificationQueryNotFoundException ex = new ClarificationQueryNotFoundException(id);
    assertThat(ex.queryId()).isEqualTo(id);
    assertThat(ex.getMessage()).contains(id.toString());
  }

  @Test
  void clarificationQueryAlreadyAnswered_exception_carriesId() {
    UUID id = UUID.fromString("44444444-4444-4444-4444-444444444444");
    ClarificationQueryAlreadyAnsweredException ex =
        new ClarificationQueryAlreadyAnsweredException(id);
    assertThat(ex.queryId()).isEqualTo(id);
    assertThat(ex.getMessage()).contains(id.toString());
  }

  @Test
  void clarificationQueryExpired_exception_carriesBothIds() {
    UUID q = UUID.fromString("55555555-5555-5555-5555-555555555555");
    UUID fb = UUID.fromString("66666666-6666-6666-6666-666666666666");
    ClarificationQueryExpiredException ex = new ClarificationQueryExpiredException(q, fb);
    assertThat(ex.queryId()).isEqualTo(q);
    assertThat(ex.feedbackEntryId()).isEqualTo(fb);
    assertThat(ex.getMessage()).contains(q.toString());
  }

  // ============================================================
  // FeedbackModule — query() / update() NO_COVERAGE NullReturnVals
  // ============================================================
  @Test
  void feedbackModule_query_returnsInjectedQueryService() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackModule module = new FeedbackModule(q, u);
    assertThat(module.query()).isSameAs(q);
    assertThat(module.update()).isSameAs(u);
  }

  // ============================================================
  // Domain entity getter NullReturnVals — RoutingLogEntry, FeedbackEntry,
  // ClarificationQuery, MisclassificationCorrection.
  // Each test sets fields via builder/setter and asserts the getter returns the same value
  // — null would surface immediately.
  // ============================================================
  @Test
  void routingLogEntry_getters_returnSetValues() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    RoutingLogEntry r = FeedbackTestData.routingLogEntry(parent);
    Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
    r.setCreatedAt(createdAt);
    // Kill getFeedbackEntry NullReturnVals.
    assertThat(r.getFeedbackEntry()).isSameAs(parent);
    // Kill getExtractedFeedback EmptyObjectReturnVals.
    assertThat(r.getExtractedFeedback()).isEqualTo("the salt was too much").isNotEmpty();
    // Kill getStructuredPayload NullReturnVals.
    assertThat(r.getStructuredPayload()).isNotNull();
    assertThat(r.getStructuredPayload().get("affectsPlan").asBoolean()).isTrue();
    // Kill getRoutingDecision NullReturnVals.
    assertThat(r.getRoutingDecision())
        .isEqualTo(com.example.mealprep.feedback.domain.entity.RoutingDecision.AUTO_ROUTED);
    // Kill getClassificationAttempt PrimitiveReturnsMutator (replace with 0).
    assertThat(r.getClassificationAttempt()).isEqualTo(1);
    // Kill getCreatedAt NullReturnVals.
    assertThat(r.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void feedbackEntry_getters_returnSetValues() {
    FeedbackEntry e = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    e.setOptimisticVersion(7L);
    Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-04-02T00:00:00Z");
    e.setCreatedAt(createdAt);
    e.setUpdatedAt(updatedAt);
    // Kill PrimitiveReturnsMutator on getOptimisticVersion (replace long with 0).
    assertThat(e.getOptimisticVersion()).isEqualTo(7L);
    // Kill NullReturnValsMutator on getCreatedAt / getUpdatedAt.
    assertThat(e.getCreatedAt()).isEqualTo(createdAt);
    assertThat(e.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void clarificationQuery_getters_returnSetValues() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    q.setOptimisticVersion(3L);
    Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
    Instant updatedAt = Instant.parse("2026-04-02T00:00:00Z");
    q.setCreatedAt(createdAt);
    q.setUpdatedAt(updatedAt);
    // Kill PrimitiveReturnsMutator on getOptimisticVersion.
    assertThat(q.getOptimisticVersion()).isEqualTo(3L);
    // Kill NullReturnValsMutator on getCreatedAt / getUpdatedAt.
    assertThat(q.getCreatedAt()).isEqualTo(createdAt);
    assertThat(q.getUpdatedAt()).isEqualTo(updatedAt);
  }

  @Test
  void misclassificationCorrection_getters_returnSetValues() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    UUID origRoutingId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    MisclassificationCorrection c =
        FeedbackTestData.misclassificationCorrection(parent, origRoutingId, actorId);
    Instant createdAt = Instant.parse("2026-04-01T00:00:00Z");
    c.setCreatedAt(createdAt);
    // Kill NullReturnValsMutator on getId / getOriginalRoutingId / getOccurredAt / getCreatedAt.
    assertThat(c.getId()).isNotNull();
    assertThat(c.getOriginalRoutingId()).isEqualTo(origRoutingId);
    assertThat(c.getOccurredAt()).isNotNull();
    assertThat(c.getCreatedAt()).isEqualTo(createdAt);
    // Kill EmptyObjectReturnValsMutator on getUserCorrectionNote (replace with "").
    assertThat(c.getUserCorrectionNote())
        .isEqualTo("this was a standing pref, not a one-off recipe note")
        .isNotEmpty();
  }

  // ============================================================
  // ValidDestinationValidator — three NO_COVERAGE on isValid()
  // ============================================================
  @Test
  void validDestination_acceptsEveryEnumValue() {
    ValidDestinationValidator v = new ValidDestinationValidator();
    for (Destination d : Destination.values()) {
      assertThat(v.isValid(d, null)).as("destination %s should validate", d).isTrue();
    }
  }

  @Test
  void validDestination_rejectsNull() {
    ValidDestinationValidator v = new ValidDestinationValidator();
    // Kills BooleanTrueReturnVals (always true) + NegateConditionals on the null check.
    assertThat(v.isValid(null, null)).isFalse();
  }

  @Test
  void validDestination_endToEndConstraintWiresThroughJakartaValidation() {
    record Holder(@ValidDestination Destination newDestination) {}
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    try {
      Validator validator = factory.getValidator();
      assertThat(validator.validate(new Holder(Destination.RECIPE))).isEmpty();
      java.util.Set<ConstraintViolation<Holder>> withNull = validator.validate(new Holder(null));
      assertThat(withNull).isNotEmpty();
    } finally {
      factory.close();
    }
  }

  // ============================================================
  // Mappers — toDtos() NO_COVERAGE for list mappers (null/empty/non-empty branches).
  // ============================================================
  @Test
  void feedbackEntryMapper_toDtos_nullList_returnsEmptyList() throws Exception {
    FeedbackEntryMapper mapper = Mappers.getMapper(FeedbackEntryMapper.class);
    injectField(mapper, "routingLogMapper", Mappers.getMapper(RoutingLogMapper.class));
    // Kills NegateConditionalsMutator on `entities == null` + EmptyObjectReturnValsMutator
    // replacing with `Collections.emptyList()` (already returns empty, so the null-branch
    // assertion below covers the negate).
    List<FeedbackEntryDto> out = mapper.toDtos(null);
    assertThat(out).isNotNull().isEmpty();
  }

  @Test
  void feedbackEntryMapper_toDtos_emptyList_returnsEmpty() throws Exception {
    FeedbackEntryMapper mapper = Mappers.getMapper(FeedbackEntryMapper.class);
    injectField(mapper, "routingLogMapper", Mappers.getMapper(RoutingLogMapper.class));
    List<FeedbackEntryDto> out = mapper.toDtos(Collections.emptyList());
    assertThat(out).isNotNull().isEmpty();
  }

  @Test
  void feedbackEntryMapper_toDtos_populatedList_mapsEachEntry() throws Exception {
    FeedbackEntryMapper mapper = Mappers.getMapper(FeedbackEntryMapper.class);
    injectField(mapper, "routingLogMapper", Mappers.getMapper(RoutingLogMapper.class));
    FeedbackEntry e1 = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "salt");
    FeedbackEntry e2 = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "sugar");
    List<FeedbackEntryDto> out = mapper.toDtos(List.of(e1, e2));
    // Kills the EmptyObjectReturnVals (replace return with emptyList) mutation — non-empty
    // input must yield non-empty output.
    assertThat(out).hasSize(2);
    assertThat(out.get(0).id()).isEqualTo(e1.getId());
    assertThat(out.get(1).id()).isEqualTo(e2.getId());
  }

  @Test
  void clarificationQueryMapper_toDtos_nullList_returnsEmpty() throws Exception {
    ClarificationQueryMapper mapper = Mappers.getMapper(ClarificationQueryMapper.class);
    injectField(mapper, "objectMapper", new ObjectMapper());
    List<ClarificationQueryDto> out = mapper.toDtos(null);
    assertThat(out).isNotNull().isEmpty();
  }

  @Test
  void clarificationQueryMapper_toDtos_emptyList_returnsEmpty() throws Exception {
    ClarificationQueryMapper mapper = Mappers.getMapper(ClarificationQueryMapper.class);
    injectField(mapper, "objectMapper", new ObjectMapper());
    List<ClarificationQueryDto> out = mapper.toDtos(Collections.emptyList());
    assertThat(out).isNotNull().isEmpty();
  }

  @Test
  void clarificationQueryMapper_toDtos_populatedList_mapsEach() throws Exception {
    ClarificationQueryMapper mapper = Mappers.getMapper(ClarificationQueryMapper.class);
    injectField(mapper, "objectMapper", new ObjectMapper());
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q1 = FeedbackTestData.clarificationQuery(parent);
    ClarificationQuery q2 = FeedbackTestData.clarificationQuery(parent);
    List<ClarificationQueryDto> out = mapper.toDtos(List.of(q1, q2));
    assertThat(out).hasSize(2);
    assertThat(out.get(0).id()).isEqualTo(q1.getId());
    // Each row's options array (size 2) round-trips — kills mutations that drop the readOptions
    // path during list mapping.
    assertThat(out.get(0).options()).hasSize(2);
  }

  // ============================================================
  // FeedbackController — six NO_COVERAGE NullReturnVals on direct method invocations.
  // Pure unit test bypassing MockMvc — instantiates the controller with mocks.
  // ============================================================
  @Test
  void feedbackController_submitFeedback_returns202WithLocationAndBody() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    UUID newFeedbackId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    SubmitFeedbackResponse stubResp =
        new SubmitFeedbackResponse(
            newFeedbackId, traceId, SubmissionStatus.RECEIVED, List.of(), null);
    when(u.submitFeedback(eq(userId), any(SubmitFeedbackRequest.class))).thenReturn(stubResp);

    FeedbackController controller = new FeedbackController(u, q, resolver);
    SubmitFeedbackRequest req = FeedbackTestData.submitFeedbackRequest("hello");

    MockHttpServletRequest httpReq = new MockHttpServletRequest();
    httpReq.setRequestURI("/api/v1/feedback");
    httpReq.setScheme("http");
    httpReq.setServerName("localhost");
    httpReq.setServerPort(80);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpReq));
    try {
      ResponseEntity<SubmitFeedbackResponse> resp = controller.submitFeedback(req);
      // Kill NullReturnValsMutator: must return the live ResponseEntity, not null.
      assertThat(resp).isNotNull();
      assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
      assertThat(resp.getBody()).isSameAs(stubResp);
      assertThat(resp.getHeaders().getLocation()).isNotNull();
      assertThat(resp.getHeaders().getLocation().getPath()).endsWith("/" + newFeedbackId);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  @Test
  void feedbackController_list_delegatesToQueryService() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    FeedbackEntry e = FeedbackTestData.feedbackEntry(userId, "x");
    FeedbackEntryMapper fem = Mappers.getMapper(FeedbackEntryMapper.class);
    try {
      injectField(fem, "routingLogMapper", Mappers.getMapper(RoutingLogMapper.class));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    FeedbackEntryDto dto = fem.toDto(e);
    Page<FeedbackEntryDto> page = new PageImpl<>(List.of(dto));
    when(q.listByUser(eq(userId), any(Pageable.class))).thenReturn(page);

    FeedbackController controller = new FeedbackController(u, q, resolver);
    Page<FeedbackEntryDto> result = controller.list(0, 20);
    // Kill NullReturnValsMutator: result must be the stub page, not null.
    assertThat(result).isSameAs(page);
    assertThat(result.getContent()).hasSize(1);
  }

  @Test
  void feedbackController_list_passesPageRequestWithPageAndSize() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    Page<FeedbackEntryDto> emptyPage = Page.empty();
    when(q.listByUser(eq(userId), any(Pageable.class))).thenReturn(emptyPage);

    FeedbackController controller = new FeedbackController(u, q, resolver);
    controller.list(3, 50);
    org.mockito.ArgumentCaptor<Pageable> pageCap =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(q).listByUser(eq(userId), pageCap.capture());
    assertThat(pageCap.getValue().getPageNumber()).isEqualTo(3);
    assertThat(pageCap.getValue().getPageSize()).isEqualTo(50);
  }

  @Test
  void feedbackController_getById_present_returnsDto() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    FeedbackEntry e = FeedbackTestData.feedbackEntry(userId, "x");
    FeedbackEntryMapper fem = Mappers.getMapper(FeedbackEntryMapper.class);
    try {
      injectField(fem, "routingLogMapper", Mappers.getMapper(RoutingLogMapper.class));
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    FeedbackEntryDto dto = fem.toDto(e);
    when(q.getById(userId, feedbackId)).thenReturn(Optional.of(dto));

    FeedbackController controller = new FeedbackController(u, q, resolver);
    FeedbackEntryDto result = controller.getById(feedbackId);
    // Kill NullReturnValsMutator on getById + on its orElseThrow lambda (only one of these
    // fires per call but the lambda mutation is dead-equivalent — the throw path is exercised
    // by the missing test below).
    assertThat(result).isSameAs(dto);
  }

  @Test
  void feedbackController_getById_missing_throwsFeedbackEntryNotFound() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    when(q.getById(userId, feedbackId)).thenReturn(Optional.empty());

    FeedbackController controller = new FeedbackController(u, q, resolver);
    assertThatThrownBy(() -> controller.getById(feedbackId))
        .isInstanceOf(FeedbackEntryNotFoundException.class)
        .hasMessageContaining(feedbackId.toString());
  }

  @Test
  void feedbackController_correct_delegatesAndReturnsResponse() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID feedbackId = UUID.randomUUID();
    UUID routingId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    SubmitFeedbackResponse stub =
        new SubmitFeedbackResponse(
            feedbackId, UUID.randomUUID(), SubmissionStatus.CORRECTED, List.of(), null);
    CorrectionRequest req = FeedbackTestData.correctionRequest(Destination.PREFERENCE, "note");
    when(u.correctMisclassification(userId, feedbackId, routingId, req)).thenReturn(stub);

    FeedbackController controller = new FeedbackController(u, q, resolver);
    SubmitFeedbackResponse result = controller.correct(feedbackId, routingId, req);
    // Kill NullReturnValsMutator.
    assertThat(result).isSameAs(stub);
  }

  @Test
  void feedbackController_listCorrections_delegatesPaginated() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    Page<MisclassificationCorrectionDto> page = new PageImpl<>(List.of());
    when(q.listCorrections(eq(userId), any(Pageable.class))).thenReturn(page);

    FeedbackController controller = new FeedbackController(u, q, resolver);
    Page<MisclassificationCorrectionDto> result = controller.listCorrections(2, 25);
    assertThat(result).isSameAs(page);
    org.mockito.ArgumentCaptor<Pageable> pageCap =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(q).listCorrections(eq(userId), pageCap.capture());
    assertThat(pageCap.getValue().getPageNumber()).isEqualTo(2);
    assertThat(pageCap.getValue().getPageSize()).isEqualTo(25);
  }

  @Test
  void feedbackController_requireCurrentUserId_anonymous_throws401() {
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentUserId()).thenReturn(Optional.empty());
    FeedbackController controller = new FeedbackController(u, q, resolver);
    // Kills NullReturnValsMutator on requireCurrentUserId / its orElseThrow lambda — the
    // anonymous branch must surface a 401, not silently fall through.
    assertThatThrownBy(() -> controller.list(0, 20))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  // ============================================================
  // ClarificationQueryController — six NO_COVERAGE NullReturnVals.
  // ============================================================
  @Test
  void clarificationController_list_delegatesWithStatusAndPageRequest() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    Page<ClarificationQueryDto> page = new PageImpl<>(List.of());
    when(q.listClarificationQueries(
            eq(userId), eq(ClarificationStatus.PENDING), any(Pageable.class)))
        .thenReturn(page);

    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    Page<ClarificationQueryDto> result = c.list(ClarificationStatus.PENDING, 1, 10);
    assertThat(result).isSameAs(page);
    org.mockito.ArgumentCaptor<Pageable> pageCap =
        org.mockito.ArgumentCaptor.forClass(Pageable.class);
    verify(q)
        .listClarificationQueries(eq(userId), eq(ClarificationStatus.PENDING), pageCap.capture());
    assertThat(pageCap.getValue().getPageNumber()).isEqualTo(1);
    assertThat(pageCap.getValue().getPageSize()).isEqualTo(10);
  }

  @Test
  void clarificationController_list_nullStatusFilter_passesThrough() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    when(q.listClarificationQueries(eq(userId), eq(null), any(Pageable.class)))
        .thenReturn(Page.empty());
    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    Page<ClarificationQueryDto> result = c.list(null, 0, 20);
    assertThat(result).isNotNull();
    verify(q).listClarificationQueries(eq(userId), eq(null), any(Pageable.class));
  }

  @Test
  void clarificationController_getById_present_returnsDto() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    ClarificationQueryDto dto =
        new ClarificationQueryDto(
            queryId,
            UUID.randomUUID(),
            "q?",
            List.of(new ClarificationOptionDto(Destination.RECIPE, "snip", "why")),
            ClarificationStatus.PENDING,
            Instant.parse("2026-05-20T00:00:00Z"),
            Instant.parse("2026-05-12T00:00:00Z"));
    when(q.getClarificationQuery(userId, queryId)).thenReturn(Optional.of(dto));

    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    ClarificationQueryDto result = c.getById(queryId);
    assertThat(result).isSameAs(dto);
  }

  @Test
  void clarificationController_getById_missing_throwsNotFound() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    when(q.getClarificationQuery(userId, queryId)).thenReturn(Optional.empty());

    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    assertThatThrownBy(() -> c.getById(queryId))
        .isInstanceOf(ClarificationQueryNotFoundException.class)
        .hasMessageContaining(queryId.toString());
  }

  @Test
  void clarificationController_answer_delegatesAndReturnsResponse() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    UUID userId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    when(resolver.currentUserId()).thenReturn(Optional.of(userId));
    SubmitFeedbackResponse stub =
        new SubmitFeedbackResponse(
            UUID.randomUUID(), UUID.randomUUID(), SubmissionStatus.RECEIVED, List.of(), null);
    AnswerClarificationRequest req =
        FeedbackTestData.answerRequest(Destination.RECIPE, "the user meant recipe");
    when(u.answerClarificationQuery(userId, queryId, req)).thenReturn(stub);

    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    SubmitFeedbackResponse result = c.answer(queryId, req);
    assertThat(result).isSameAs(stub);
  }

  @Test
  void clarificationController_anonymous_throws401() {
    FeedbackQueryService q = mock(FeedbackQueryService.class);
    FeedbackUpdateService u = mock(FeedbackUpdateService.class);
    CurrentUserResolver resolver = mock(CurrentUserResolver.class);
    when(resolver.currentUserId()).thenReturn(Optional.empty());
    ClarificationQueryController c = new ClarificationQueryController(q, u, resolver);
    assertThatThrownBy(() -> c.list(null, 0, 20))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  // ============================================================
  // Helpers
  // ============================================================
  private static void injectField(Object target, String fieldName, Object value) throws Exception {
    Class<?> klass = target.getClass();
    while (klass != null) {
      try {
        Field f = klass.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
        return;
      } catch (NoSuchFieldException ignored) {
        klass = klass.getSuperclass();
      }
    }
    throw new IllegalStateException("field not found: " + fieldName);
  }

  // Empty placeholders so @BeforeAll/@AfterAll machinery compiles even if unused.
  @BeforeAll
  static void beforeAll() {}

  @AfterAll
  static void afterAll() {}
}
