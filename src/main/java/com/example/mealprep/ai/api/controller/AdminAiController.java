package com.example.mealprep.ai.api.controller;

import com.example.mealprep.ai.api.AiAdminGuard;
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
 * <p><b>Authorisation (finding {@code ai-10}).</b> {@code @PreAuthorize("hasRole('ADMIN')")} is
 * declared on every method as the published contract, but it is <em>inert</em> in v1: the project
 * does not enable Spring method-security ({@code @EnableMethodSecurity} is absent) and the flat
 * user model has no {@code ROLE_ADMIN} authority. Enforcement is therefore done imperatively via
 * {@link AiAdminGuard#requireAdmin()} at the top of every handler — anonymous callers get 401 (also
 * enforced by the deny-by-default {@code AuthSecurityConfig} chain) and authenticated-but-not-admin
 * callers get 403, gated on the {@code mealprep.ai.admin.user-ids} allowlist (fail-closed: empty by
 * default ⇒ no non-admin reaches these endpoints). When project-wide method-security lands, the
 * {@code @PreAuthorize} annotations activate and this guard can be retired.
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@Validated
@Tag(name = "AdminAi", description = "Admin observability for AI calls and prompt templates.")
public class AdminAiController {

  private final AdminAiQueryService queryService;
  private final PromptTemplateService promptTemplateService;
  private final AiAdminGuard adminGuard;

  public AdminAiController(
      AdminAiQueryService queryService,
      PromptTemplateService promptTemplateService,
      AiAdminGuard adminGuard) {
    this.queryService = queryService;
    this.promptTemplateService = promptTemplateService;
    this.adminGuard = adminGuard;
  }

  @GetMapping("/cost-summary")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(
      summary = "Sum AI costs across all users + per-user breakdown for top 20 spenders.",
      description = "Window is in hours and clamped to [1, 720] (30 days).")
  public CostSummaryDto getCostSummary(
      @RequestParam(defaultValue = "24") @Min(1) @Max(720) int windowHours) {
    adminGuard.requireAdmin();
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
    adminGuard.requireAdmin();
    Pageable pageable = PageRequest.of(page, size);
    return queryService.getCallLog(taskType, userId, pageable);
  }

  @GetMapping("/prompt-templates")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Paginated list of all prompt template versions.")
  public Page<PromptTemplateDto> listPromptTemplates(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    adminGuard.requireAdmin();
    Pageable pageable = PageRequest.of(page, size);
    return promptTemplateService.listAll(pageable);
  }

  @GetMapping("/prompt-templates/{name}/{version}")
  @PreAuthorize("hasRole('ADMIN')")
  @Operation(summary = "Get a single prompt template by (name, version).")
  public PromptTemplateDto getPromptTemplate(
      @PathVariable String name, @PathVariable @Min(1) int version) {
    adminGuard.requireAdmin();
    return promptTemplateService.get(name, version);
  }
}
