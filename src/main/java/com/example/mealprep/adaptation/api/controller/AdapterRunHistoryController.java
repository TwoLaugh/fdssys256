package com.example.mealprep.adaptation.api.controller;

import com.example.mealprep.adaptation.api.dto.AdaptationJobDto;
import com.example.mealprep.adaptation.api.dto.AdaptationTraceDto;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quality-dashboard data feed for the adaptation pipeline — two admin-gated read endpoints (run
 * history by source + time window; traces by prompt-template version).
 *
 * <p>{@code @PreAuthorize("hasRole('ROLE_ADMIN')")} per LLD line 578 (enforcement owned by the auth
 * module — out of scope per ticket 01f §What's NOT in scope; mirrors {@code
 * AdminDecisionLogController}).
 *
 * <p>Per ticket 01f §AdapterRunHistoryController and {@code lld/adaptation-pipeline.md} lines
 * 613-618.
 */
@RestController
@RequestMapping("/api/v1/adaptation/run-history")
@Validated
@Tag(name = "Adaptation")
public class AdapterRunHistoryController {

  private final AdaptationQueryService queryService;

  public AdapterRunHistoryController(AdaptationQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: run history filtered by source + enqueue-time window, newest first.")
  public Page<AdaptationJobDto> runHistory(
      @RequestParam JobSource source,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queryService.getRunHistory(source, from, to, PageRequest.of(page, size));
  }

  @GetMapping(path = "/by-prompt-version", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasRole('ROLE_ADMIN')")
  @Operation(summary = "Admin: traces filtered by prompt-template name + version, newest first.")
  public Page<AdaptationTraceDto> byPromptVersion(
      @RequestParam String name,
      @RequestParam String version,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return queryService.getTracesForPromptVersion(name, version, PageRequest.of(page, size));
  }
}
