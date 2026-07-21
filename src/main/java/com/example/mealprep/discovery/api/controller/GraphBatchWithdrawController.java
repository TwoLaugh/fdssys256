package com.example.mealprep.discovery.api.controller;

import com.example.mealprep.auth.api.AdminAccessGuard;
import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.discovery.api.dto.GraphBatchWithdrawReport;
import com.example.mealprep.recipe.api.dto.ImportJobArchiveResult;
import com.example.mealprep.recipe.domain.service.RecipeUpdateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * G11 (graph integration): admin withdraw/restore for a live graph batch by its per-batch {@code
 * jobId} (one jobId per batch — G06 invariant, recorded on every {@code recipe_imports} row and in
 * the batch's {@code ingest_report.json}). Uses the archive lever through the sanctioned {@code
 * RecipeUpdateService} seam — {@code archived_at} is excluded from every plannable read, so a
 * withdrawn batch leaves the pool immediately while rows, provenance, embeddings and ratings
 * survive for a full restore. Reversible, idempotent, admin-gated like the ingest endpoint.
 *
 * <p>Scope: only {@code sourceType = AI_GENERATED} import rows match — a graph withdraw must never
 * sweep a discovery crawl's harvest (which shares the {@code job_id} column). Unknown jobId (or a
 * jobId with no AI_GENERATED rows) ⇒ 404 so a mistyped id is loud, not a silent no-op. Flag note:
 * flipping {@code mealprep.graph.import.enabled=false} stops future ingest but does NOT retro-hide
 * dishes — this endpoint is the retro lever (see the property comment + G11 runbook).
 */
@RestController
@RequestMapping("/api/v1/discovery/admin/graph-batches")
@Tag(name = "Discovery")
public class GraphBatchWithdrawController {

  private final RecipeUpdateService recipeUpdateService;
  private final AdminAccessGuard adminGuard;
  private final CurrentUserResolver currentUserResolver;

  public GraphBatchWithdrawController(
      RecipeUpdateService recipeUpdateService,
      AdminAccessGuard adminGuard,
      CurrentUserResolver currentUserResolver) {
    this.recipeUpdateService = recipeUpdateService;
    this.adminGuard = adminGuard;
    this.currentUserResolver = currentUserResolver;
  }

  @PostMapping("/{jobId}/withdraw")
  @Operation(
      summary =
          "Admin: withdraw a live graph batch — reversibly archive every AI_GENERATED recipe"
              + " imported under this jobId (G11). Archived rows leave all plannable reads"
              + " immediately; already-generated plans keep their slots. Idempotent; 404 on an"
              + " unknown jobId.")
  public GraphBatchWithdrawReport withdraw(@PathVariable UUID jobId) {
    adminGuard.requireAdmin();
    ImportJobArchiveResult result =
        recipeUpdateService.archiveByImportJobId(jobId, currentUserId());
    return report(jobId, GraphBatchWithdrawReport.ACTION_WITHDRAWN, result);
  }

  @PostMapping("/{jobId}/restore")
  @Operation(
      summary =
          "Admin: restore a withdrawn graph batch — clear archived_at on the same jobId match set"
              + " (G11). Full restore: embeddings/ratings intact. Idempotent; 404 on an unknown"
              + " jobId.")
  public GraphBatchWithdrawReport restore(@PathVariable UUID jobId) {
    adminGuard.requireAdmin();
    ImportJobArchiveResult result =
        recipeUpdateService.unarchiveByImportJobId(jobId, currentUserId());
    return report(jobId, GraphBatchWithdrawReport.ACTION_RESTORED, result);
  }

  private UUID currentUserId() {
    // requireAdmin() has already rejected anonymous callers; this is the audit-log identity.
    return currentUserResolver.currentUserId().orElse(null);
  }

  private static GraphBatchWithdrawReport report(
      UUID jobId, String action, ImportJobArchiveResult result) {
    if (result.matchedRecipeIds().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "No AI_GENERATED import rows carry jobId "
              + jobId
              + " — not a known graph batch (discovery-crawl jobIds are deliberately out of"
              + " scope).");
    }
    return new GraphBatchWithdrawReport(
        jobId,
        action,
        result.matchedRecipeIds().size(),
        result.changedRecipeIds(),
        result.skippedRecipeIds());
  }
}
