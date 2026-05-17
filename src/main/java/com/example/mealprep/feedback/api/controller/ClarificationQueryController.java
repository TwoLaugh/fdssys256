package com.example.mealprep.feedback.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.exception.ClarificationQueryNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Secondary REST seam for the feedback module: the clarification queue (Flow 5). Authentication is
 * enforced by the auth module's deny-by-default chain; the {@link CurrentUserResolver} resolves the
 * caller's {@code userId} server-side — every query is scoped to that user.
 *
 * <p>{@code POST .../answer} returns HTTP 200 with the pre-re-classification receipt (entry status
 * {@code RECEIVED}, empty routes). Re-classification runs asynchronously afterwards; the client
 * polls {@code GET /api/v1/feedback/{feedbackId}} for progression.
 */
@RestController
@RequestMapping("/api/v1/feedback/clarifications")
@Tag(name = "Feedback - clarifications")
public class ClarificationQueryController {

  private final FeedbackQueryService queryService;
  private final FeedbackUpdateService updateService;
  private final CurrentUserResolver currentUserResolver;

  public ClarificationQueryController(
      FeedbackQueryService queryService,
      FeedbackUpdateService updateService,
      CurrentUserResolver currentUserResolver) {
    this.queryService = queryService;
    this.updateService = updateService;
    this.currentUserResolver = currentUserResolver;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Paginated list of the caller's clarification queries; optional status filter.")
  public Page<ClarificationQueryDto> list(
      @RequestParam(required = false) ClarificationStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    return queryService.listClarificationQueries(userId, status, PageRequest.of(page, size));
  }

  @GetMapping(path = "/{queryId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch a single clarification query.")
  public ClarificationQueryDto getById(@PathVariable UUID queryId) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getClarificationQuery(userId, queryId)
        .orElseThrow(() -> new ClarificationQueryNotFoundException(queryId));
  }

  @PostMapping(
      path = "/{queryId}/answer",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Answer a pending clarification; re-classification fires automatically afterwards.")
  public SubmitFeedbackResponse answer(
      @PathVariable UUID queryId, @Valid @RequestBody AnswerClarificationRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.answerClarificationQuery(userId, queryId, request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
