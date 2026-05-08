package com.example.mealprep.ai.api.controller;

import com.example.mealprep.ai.api.dto.AiCallLogDto;
import com.example.mealprep.ai.api.dto.CostSummaryDto;
import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.domain.service.AdminAiQueryService;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import com.example.mealprep.ai.spi.TaskType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin observability endpoints for the AI module.
 *
 * <p>{@code @PreAuthorize("hasRole('ADMIN')")} is present on every method but is not yet enforced.
 * Auth-01a wires the filter chain so anonymous requests now return 401, but role gating still
 * passes for any authenticated user — the flat user model has no ROLE_ADMIN authority yet.
 * <strong>TODO(auth-roles-followup):</strong> introduce ROLE_ADMIN once the household-admin model
 * lands and verify these endpoints reject non-admin authenticated users.
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@Validated
@Tag(name = "AdminAi", description = "Admin observability for AI calls and prompt templates.")
public class AdminAiController {

  private final AdminAiQueryService queryService;
  private final PromptTemplateService promptTemplateService;

  public AdminAiController(
      AdminAiQueryService queryService, PromptTemplateService promptTemplateService) {
    this.queryService = queryService;
    this.promptTemplateService = promptTemplateService;
  }

  @GetMapping("/cost-summary")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Sum AI costs across all users + per-user breakdown for top 20 spenders.",
      description = "Window is in hours and clamped to [1, 720] (30 days).")
  public CostSummaryDto getCostSummary(
      @RequestParam(defaultValue = "24") @Min(1) @Max(720) int windowHours) {
    return queryService.getCostSummary(windowHours);
  }

  @GetMapping("/call-log")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Paginated AI call audit log; newest-first; optional filters.")
  public Page<AiCallLogDto> getCallLog(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) TaskType taskType,
      @RequestParam(required = false) UUID userId) {
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getCallLog(taskType, userId, pageable);
  }

  @GetMapping("/prompt-templates")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Paginated list of all prompt template versions.")
  public Page<PromptTemplateDto> listPromptTemplates(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    Pageable pageable = PageRequest.of(page, size);
    return promptTemplateService.listAll(pageable);
  }

  @GetMapping("/prompt-templates/{name}/{version}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Get a single prompt template by (name, version).")
  public PromptTemplateDto getPromptTemplate(
      @PathVariable String name, @PathVariable @Min(1) int version) {
    return promptTemplateService.get(name, version);
  }
}
