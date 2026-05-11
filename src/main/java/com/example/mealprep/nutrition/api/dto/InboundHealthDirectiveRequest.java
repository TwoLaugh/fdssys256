package com.example.mealprep.nutrition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/nutrition/health-directives/inbound}. The health platform tells us
 * the target {@code userId} (which may differ from the authenticated caller). Idempotent on {@code
 * (sourcePlatform, externalDirectiveId)}.
 */
public record InboundHealthDirectiveRequest(
    @NotNull UUID userId,
    @NotBlank @Size(max = 128) String externalDirectiveId,
    @NotBlank @Size(max = 64) String sourcePlatform,
    @NotNull DirectiveType directiveType,
    @Size(max = 4000) String evidenceSummary,
    DirectiveConfidence evidenceConfidence,
    @NotNull @Valid DirectiveInstructionDocument instruction,
    @NotBlank @Size(max = 24) String mapsToModel,
    @Size(max = 48) String mapsToTier,
    boolean temporary,
    Instant autoExpiresAt) {}
