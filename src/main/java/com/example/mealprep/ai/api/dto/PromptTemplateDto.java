package com.example.mealprep.ai.api.dto;

import com.example.mealprep.ai.spi.ModelTier;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Public DTO for the {@code ai_prompt_template} table. Returned by the admin endpoints; the
 * structured fields ({@code outputSchema}, {@code tools}) are pass-through {@link JsonNode}
 * payloads since their shape is defined per-prompt rather than centrally.
 */
public record PromptTemplateDto(
    UUID id,
    String name,
    int version,
    ModelTier modelTier,
    String systemPrompt,
    String userPromptTemplate,
    JsonNode outputSchema,
    JsonNode tools,
    String notes,
    String sourceFile,
    String sourceHash,
    Instant createdAt) {}
