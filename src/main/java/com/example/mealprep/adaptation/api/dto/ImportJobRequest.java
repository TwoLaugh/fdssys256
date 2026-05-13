package com.example.mealprep.adaptation.api.dto;

import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.recipe.domain.entity.DataQuality;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Trigger-1 request — recipe-import entry. Async; the adaptation worker pipeline picks up the
 * enqueued job and processes it.
 *
 * <p>Per LLD §DTOs lines 314-317; verbatim from {@code lld/adaptation-pipeline.md}.
 */
public record ImportJobRequest(
    @NotNull UUID recipeId,
    @NotNull UUID userId,
    @NotNull Catalogue catalogue,
    @NotNull DataQuality dataQuality,
    @Nullable JsonNode rawImportContext,
    @Nullable UUID parentTraceId) {}
