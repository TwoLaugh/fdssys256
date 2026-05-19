package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.mealprep.ai.api.dto.PromptTemplateDto;
import com.example.mealprep.ai.api.mapper.PromptTemplateMapper;
import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.domain.repository.PromptTemplateRepository;
import com.example.mealprep.ai.domain.service.RenderedPrompt;
import com.example.mealprep.ai.domain.service.internal.PromptTemplateRenderer;
import com.example.mealprep.ai.spi.ModelTier;
import com.example.mealprep.ai.spi.PromptRef;
import com.example.mealprep.ai.spi.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@code PromptTemplateServiceImpl}. Real {@link PromptTemplateRenderer} + {@link
 * ObjectMapper} are used (playbook: never mock collaborators inside the module under test); the
 * repository and the generated MapStruct mapper are legitimate cross-class mocks. Focus is the
 * {@code render(...)} path and the {@code readTools(...)} JSON-array branch matrix where the
 * baseline left 17/17 mutants uncovered.
 */
@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceImplTest {

  @Mock private PromptTemplateRepository repository;
  @Mock private PromptTemplateMapper mapper;

  private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
  private final ObjectMapper objectMapper = new ObjectMapper();

  private Object service;

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    Class<?> impl =
        Class.forName("com.example.mealprep.ai.domain.service.internal.PromptTemplateServiceImpl");
    Constructor<?> ctor =
        impl.getDeclaredConstructor(
            PromptTemplateRepository.class,
            PromptTemplateMapper.class,
            PromptTemplateRenderer.class,
            ObjectMapper.class);
    ctor.setAccessible(true);
    service = ctor.newInstance(repository, mapper, renderer, objectMapper);
  }

  private com.example.mealprep.ai.domain.service.PromptTemplateService svc() {
    return (com.example.mealprep.ai.domain.service.PromptTemplateService) service;
  }

  private PromptTemplate template(
      String name, int version, String system, String user, JsonNode tools, JsonNode outSchema) {
    return new PromptTemplate(
        UUID.randomUUID(),
        name,
        version,
        ModelTier.CHEAP,
        system,
        user,
        outSchema,
        tools,
        "notes",
        "prompts/" + name + ".md",
        "deadbeef");
  }

  private PromptTemplateDto dtoFor(PromptTemplate t) {
    return new PromptTemplateDto(
        t.getId(),
        t.getName(),
        t.getVersion(),
        t.getModelTier(),
        t.getSystemPrompt(),
        t.getUserPromptTemplate(),
        t.getOutputSchema(),
        t.getTools(),
        "notes",
        t.getSourceFile(),
        t.getSourceHash(),
        null);
  }

  // ---------------- get / getLatest / listAll ----------------

  @Test
  void get_returnsMappedDto_whenFound() {
    PromptTemplate t = template("classify", 2, "sys", "usr", null, null);
    when(repository.findByNameAndVersion("classify", 2)).thenReturn(Optional.of(t));
    PromptTemplateDto dto = dtoFor(t);
    when(mapper.toDto(t)).thenReturn(dto);

    assertThat(svc().get("classify", 2)).isSameAs(dto);
  }

  @Test
  void get_throws404_whenMissing() {
    when(repository.findByNameAndVersion("nope", 9)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc().get("nope", 9))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404))
        .hasMessageContaining("nope v9");
  }

  @Test
  void getLatest_returnsMappedDto_whenFound() {
    PromptTemplate t = template("classify", 5, "sys", "usr", null, null);
    when(repository.findFirstByNameOrderByVersionDesc("classify")).thenReturn(Optional.of(t));
    PromptTemplateDto dto = dtoFor(t);
    when(mapper.toDto(t)).thenReturn(dto);

    assertThat(svc().getLatest("classify")).isSameAs(dto);
  }

  @Test
  void getLatest_throws404_whenMissing() {
    when(repository.findFirstByNameOrderByVersionDesc("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc().getLatest("ghost"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(404))
        .hasMessageContaining("ghost");
  }

  @Test
  void listAll_mapsEachEntity() {
    PromptTemplate t = template("classify", 1, "sys", "usr", null, null);
    PageImpl<PromptTemplate> page = new PageImpl<>(List.of(t));
    when(repository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(page);
    PromptTemplateDto dto = dtoFor(t);
    when(mapper.toDto(t)).thenReturn(dto);

    Page<PromptTemplateDto> result = svc().listAll(PageRequest.of(0, 20));

    assertThat(result.getContent()).containsExactly(dto);
  }

  // ---------------- render ----------------

  @Test
  void render_nullRef_throwsIllegalArgument() {
    assertThatThrownBy(() -> svc().render(null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PromptRef must not be null");
  }

  @Test
  void render_missingTemplate_throws404() {
    PromptRef ref = new PromptRef("classify", 3);
    when(repository.findByNameAndVersion("classify", 3)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc().render(ref, Map.of()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("classify v3");
  }

  @Test
  void render_substitutesVariablesIntoSystemAndUserPrompts_andReturnsOutputSchema() {
    ObjectNode outSchema = JsonNodeFactory.instance.objectNode();
    outSchema.put("type", "object");
    PromptTemplate t =
        template("classify", 1, "You are {{role}}.", "Classify: {{text}}", null, outSchema);
    PromptRef ref = new PromptRef("classify", 1);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(ref, Map.of("role", "a classifier", "text", "too salty"));

    assertThat(rp.systemPrompt()).isEqualTo("You are a classifier.");
    assertThat(rp.userPrompt()).isEqualTo("Classify: too salty");
    assertThat(rp.tools()).isEmpty();
    assertThat(rp.expectedOutputSchema()).isSameAs(outSchema);
  }

  @Test
  void render_missingVariable_propagatesRendererFailure() {
    PromptTemplate t = template("classify", 1, "Hi {{name}}", "x", null, null);
    PromptRef ref = new PromptRef("classify", 1);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    assertThatThrownBy(() -> svc().render(ref, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing prompt variable 'name'");
  }

  // ---------------- readTools branch matrix ----------------

  @Test
  void readTools_nullToolsNode_yieldsEmptyList() {
    PromptTemplate t = template("classify", 1, "s", "u", null, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).isEmpty();
  }

  @Test
  void readTools_jsonNullNode_yieldsEmptyList() {
    PromptTemplate t = template("classify", 1, "s", "u", JsonNodeFactory.instance.nullNode(), null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).isEmpty();
  }

  @Test
  void readTools_nonArrayNode_yieldsEmptyList() {
    ObjectNode notArray = JsonNodeFactory.instance.objectNode();
    notArray.put("oops", true);
    PromptTemplate t = template("classify", 1, "s", "u", notArray, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).isEmpty();
  }

  @Test
  void readTools_validTool_isParsedWithNameDescriptionAndSchema() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("type", "object");
    ObjectNode tool = JsonNodeFactory.instance.objectNode();
    tool.put("name", "emit_classification");
    tool.put("description", "structured output tool");
    tool.set("input_schema", schema);
    tools.add(tool);
    PromptTemplate t = template("classify", 1, "s", "u", tools, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).hasSize(1);
    ToolDefinition td = rp.tools().get(0);
    assertThat(td.name()).isEqualTo("emit_classification");
    assertThat(td.description()).isEqualTo("structured output tool");
    assertThat(td.inputSchema().path("type").asText()).isEqualTo("object");
  }

  @Test
  void readTools_missingInputSchema_defaultsToEmptyObject() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    ObjectNode tool = JsonNodeFactory.instance.objectNode();
    tool.put("name", "no_schema_tool");
    // no description, no input_schema
    tools.add(tool);
    PromptTemplate t = template("classify", 1, "s", "u", tools, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).hasSize(1);
    ToolDefinition td = rp.tools().get(0);
    assertThat(td.name()).isEqualTo("no_schema_tool");
    assertThat(td.description()).isEmpty();
    assertThat(td.inputSchema()).isNotNull();
    assertThat(td.inputSchema().isObject()).isTrue();
    assertThat(td.inputSchema().size()).isZero();
  }

  @Test
  void readTools_explicitNullInputSchema_defaultsToEmptyObject() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    ObjectNode tool = JsonNodeFactory.instance.objectNode();
    tool.put("name", "null_schema_tool");
    tool.set("input_schema", JsonNodeFactory.instance.nullNode());
    tools.add(tool);
    PromptTemplate t = template("classify", 1, "s", "u", tools, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).hasSize(1);
    assertThat(rp.tools().get(0).inputSchema().isObject()).isTrue();
  }

  @Test
  void readTools_toolWithMissingName_isSkipped() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    ObjectNode noName = JsonNodeFactory.instance.objectNode();
    noName.put("description", "has no name");
    tools.add(noName);
    PromptTemplate t = template("classify", 1, "s", "u", tools, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).isEmpty();
  }

  @Test
  void readTools_toolWithBlankName_isSkipped_butValidSiblingKept() {
    ArrayNode tools = JsonNodeFactory.instance.arrayNode();
    ObjectNode blank = JsonNodeFactory.instance.objectNode();
    blank.put("name", "   ");
    tools.add(blank);
    ObjectNode good = JsonNodeFactory.instance.objectNode();
    good.put("name", "keep_me");
    tools.add(good);
    PromptTemplate t = template("classify", 1, "s", "u", tools, null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).hasSize(1);
    assertThat(rp.tools().get(0).name()).isEqualTo("keep_me");
  }

  @Test
  void readTools_emptyArray_yieldsEmptyImmutableList() {
    PromptTemplate t =
        template("classify", 1, "s", "u", JsonNodeFactory.instance.arrayNode(), null);
    when(repository.findByNameAndVersion("classify", 1)).thenReturn(Optional.of(t));

    RenderedPrompt rp = svc().render(new PromptRef("classify", 1), Map.of());

    assertThat(rp.tools()).isEmpty();
    assertThatThrownBy(() -> rp.tools().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
