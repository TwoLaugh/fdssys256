package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Result of rendering a prompt template against a variable map. {@code expectedOutputSchema} is
 * {@code null} for free-text outputs.
 */
public record RenderedPrompt(
    String systemPrompt,
    String userPrompt,
    List<ToolDefinition> tools,
    JsonNode expectedOutputSchema) {}
