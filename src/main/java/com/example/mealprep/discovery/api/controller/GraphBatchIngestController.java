package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.api.AdminAccessGuard;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestReport;
import com.example.mealprep.discovery.api.dto.GraphBatchIngestRequest;
import com.example.mealprep.discovery.domain.service.GraphBatchIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * G06 (graph integration): admin entrypoint for graph-batch ingest — NON-e2e, gated by the shared
 * {@link AdminAccessGuard} (config {@code mealprep.admin.user-ids}); anonymous ⇒ 401,
 * authenticated-but-not-allowlisted ⇒ 403. The body carries a server-local absolute path to the
 * exported batch directory (D4 option (a): single host, one operator, the review queue is the
 * throttle).
 *
 * <p>HTTP mapping: {@code OK} ⇒ 200 (per-dish rejections ride in the report); every other status
 * ({@code DISABLED}, {@code INVALID_BATCH}, {@code ABORTED_MISSING_KEYS}, {@code
 * REJECTED_RESTRICTED_DIET}) ⇒ 409 with the report as body — the batch conflicts with server state
 * (flag, seed ordering, review binding) and zero writes happened.
 */
@RestController
@RequestMapping("/api/v1/discovery/admin/graph-batches")
@Tag(name = "Discovery")
public class GraphBatchIngestController {

  private final GraphBatchIngestService ingestService;
  private final AdminAccessGuard adminGuard;

  public GraphBatchIngestController(
      GraphBatchIngestService ingestService, AdminAccessGuard adminGuard) {
    this.ingestService = ingestService;
    this.adminGuard = adminGuard;
  }

  @PostMapping(
      path = "/ingest",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary =
          "Admin: ingest an exported graph-batch directory (G06). Imports only review-approved"
              + " fingerprints; fail-closed validation; fingerprint-dedup idempotent; one jobId"
              + " per batch. Non-OK statuses return 409 with zero writes.")
  public ResponseEntity<GraphBatchIngestReport> ingest(
      @Valid @RequestBody GraphBatchIngestRequest request) {
    adminGuard.requireAdmin();
    GraphBatchIngestReport report = ingestService.ingest(request.batchPath());
    HttpStatus status =
        GraphBatchIngestReport.STATUS_OK.equals(report.status())
            ? HttpStatus.OK
            : HttpStatus.CONFLICT;
    return ResponseEntity.status(status).body(report);
  }
}
