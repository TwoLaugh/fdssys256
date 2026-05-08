package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.api.mapper.PromptTemplateMapper;
import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.domain.repository.PromptTemplateRepository;
import com.example.mealprep.ai.domain.service.PromptTemplateService;
import com.example.mealprep.ai.domain.service.RenderedPrompt;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-side {@link PromptTemplateService}. Resolves {@link PromptRef} values against the {@code
 * ai_prompt_template} table and renders templates via {@link PromptTemplateRenderer}. The loader
 * ({@link PromptTemplateLoader}) owns writes.
 */
@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

  private final PromptTemplateRepository repository;
  private final PromptTemplateMapper mapper;
  private final PromptTemplateRenderer renderer;
  private final ObjectMapper objectMapper;

  public PromptTemplateServiceImpl(
      PromptTemplateRepository repository,
      PromptTemplateMapper mapper,
      PromptTemplateRenderer renderer,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.mapper = mapper;
    this.renderer = renderer;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public PromptTemplateDto get(String name, int version) {
    PromptTemplate entity =
        repository
            .findByNameAndVersion(name, version)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Prompt template not found: " + name + " v" + version));
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public PromptTemplateDto getLatest(String name) {
    PromptTemplate entity =
        repository
            .findFirstByNameOrderByVersionDesc(name)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Prompt template not found: " + name));
    return mapper.toDto(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<PromptTemplateDto> listAll(Pageable pageable) {
    return repository.findAll(pageable).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public RenderedPrompt render(PromptRef ref, Map<String, Object> variables) {
    if (ref == null) {
      throw new IllegalArgumentException("PromptRef must not be null");
    }
    PromptTemplate entity =
        repository
            .findByNameAndVersion(ref.name(), ref.version())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Prompt template not found: " + ref.name() + " v" + ref.version()));
    String renderedSystem = renderer.render(entity.getSystemPrompt(), variables);
    String renderedUser = renderer.render(entity.getUserPromptTemplate(), variables);
    List<ToolDefinition> tools = readTools(entity.getTools());
    return new RenderedPrompt(renderedSystem, renderedUser, tools, entity.getOutputSchema());
  }

  private List<ToolDefinition> readTools(JsonNode toolsNode) {
    if (toolsNode == null || toolsNode.isNull() || !toolsNode.isArray()) {
      return List.of();
    }
    List<ToolDefinition> tools = new ArrayList<>(toolsNode.size());
    Iterator<JsonNode> it = toolsNode.elements();
    while (it.hasNext()) {
      JsonNode element = it.next();
      String name = element.path("name").asText(null);
      String description = element.path("description").asText("");
      JsonNode inputSchema = element.path("input_schema");
      if (inputSchema.isMissingNode() || inputSchema.isNull()) {
        inputSchema = objectMapper.createObjectNode();
      }
      if (name == null || name.isBlank()) {
        continue;
      }
      tools.add(new ToolDefinition(name, description, inputSchema));
    }
    return List.copyOf(tools);
  }
}
