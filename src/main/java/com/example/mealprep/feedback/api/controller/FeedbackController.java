package com.example.mealprep.feedback.api.controller;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import com.example.mealprep.feedback.domain.service.FeedbackQueryService;
import com.example.mealprep.feedback.domain.service.FeedbackUpdateService;
import com.example.mealprep.feedback.exception.FeedbackEntryNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST seam for the feedback module. Authentication is enforced by the auth module's deny-by-
 * default chain; the {@link CurrentUserResolver} resolves the caller's {@code userId} server-side —
 * the controller never accepts a {@code userId} from path or query.
 *
 * <p>{@code POST} returns HTTP 202 because the resource is created BUT processing is incomplete —
 * routing populates asynchronously as 01c/01d land. The {@code Location} header lets the client
 * poll {@code GET /api/v1/feedback/{feedbackId}} for progress.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@Tag(name = "Feedback")
public class FeedbackController {

  private final FeedbackUpdateService updateService;
  private final FeedbackQueryService queryService;
  private final CurrentUserResolver currentUserResolver;

  public FeedbackController(
      FeedbackUpdateService updateService,
      FeedbackQueryService queryService,
      CurrentUserResolver currentUserResolver) {
    this.updateService = updateService;
    this.queryService = queryService;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Submit free-text feedback.",
      description =
          "Returns HTTP 202: the entry is persisted in RECEIVED state and classification runs "
              + "asynchronously. Poll the Location header for routing progression.")
  public ResponseEntity<SubmitFeedbackResponse> submitFeedback(
      @Valid @RequestBody SubmitFeedbackRequest request) {
    UUID userId = requireCurrentUserId();
    SubmitFeedbackResponse response = updateService.submitFeedback(userId, request);
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{id}")
            .buildAndExpand(response.feedbackId())
            .toUri();
    return ResponseEntity.accepted().location(location).body(response);
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Paginated list of the caller's feedback entries, newest-first.")
  public Page<FeedbackEntryDto> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    return queryService.listByUser(userId, PageRequest.of(page, size));
  }

  @GetMapping(path = "/{feedbackId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Fetch one feedback entry with its routing log.")
  public FeedbackEntryDto getById(@PathVariable UUID feedbackId) {
    UUID userId = requireCurrentUserId();
    return queryService
        .getById(userId, feedbackId)
        .orElseThrow(() -> new FeedbackEntryNotFoundException(feedbackId));
  }

  @PostMapping(
      path = "/{feedbackId}/routes/{routingId}/correct",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "User-driven correction of a single routing decision.",
      description =
          "Cancels the original route best-effort, re-fires the corrected destination, records "
              + "ground truth. 200 / 400 / 404 / 422.")
  public SubmitFeedbackResponse correct(
      @PathVariable UUID feedbackId,
      @PathVariable UUID routingId,
      @Valid @RequestBody CorrectionRequest request) {
    UUID userId = requireCurrentUserId();
    return updateService.correctMisclassification(userId, feedbackId, routingId, request);
  }

  @GetMapping(path = "/corrections", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Paginated list of the caller's misclassification corrections; ground-truth audit.")
  public Page<MisclassificationCorrectionDto> listCorrections(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    UUID userId = requireCurrentUserId();
    return queryService.listCorrections(userId, PageRequest.of(page, size));
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
