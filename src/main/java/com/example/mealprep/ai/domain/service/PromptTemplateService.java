package com.example.mealprep.ai.domain.service;

import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.spi.PromptRef;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Cross-module service that resolves {@link PromptRef} values against the {@code
 * ai_prompt_template} table and renders templates to final prompt strings.
 *
 * <p>The loader runs at startup and INSERTs new versions when source files change; this service is
 * the read seam. {@link #render} performs Mustache-style {@code {{variable}}} substitution and
 * throws {@link IllegalArgumentException} when the variables map is missing a referenced key.
 */
public interface PromptTemplateService {

  PromptTemplateDto get(String name, int version);

  PromptTemplateDto getLatest(String name);

  Page<PromptTemplateDto> listAll(Pageable pageable);

  RenderedPrompt render(PromptRef ref, Map<String, Object> variables);
}
