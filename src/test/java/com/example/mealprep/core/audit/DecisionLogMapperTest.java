package com.example.mealprep.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.core.audit.api.dto.DecisionLogDto;
import com.example.mealprep.core.audit.api.dto.DecisionLogScale;
import com.example.mealprep.core.audit.api.mapper.DecisionLogMapper;
import com.example.mealprep.core.audit.domain.entity.DecisionLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/**
 * Unit test for the MapStruct-generated {@link DecisionLogMapper} impl. Uses the real generated
 * mapper (no mocks). Covers the null-entity guard, full field mapping, the null-list guard, and the
 * list-mapping path that PIT flagged uncovered on {@code DecisionLogMapperImpl}.
 */
class DecisionLogMapperTest {

  private final DecisionLogMapper mapper = Mappers.getMapper(DecisionLogMapper.class);

  private DecisionLog entity(UUID id) {
    JsonNode in = JsonNodeFactory.instance.objectNode().put("k", "v");
    return new DecisionLog(
        id,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "RECIPE",
        UUID.randomUUID(),
        DecisionLogScale.RECIPE,
        "trigger",
        UUID.randomUUID(),
        in,
        JsonNodeFactory.instance.arrayNode().add("c"),
        JsonNodeFactory.instance.objectNode().put("pick", "c"),
        "because",
        JsonNodeFactory.instance.objectNode().put("d", 1),
        3,
        42);
  }

  @Test
  void toDto_null_entity_returns_null() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_maps_all_scalar_fields() {
    UUID id = UUID.randomUUID();
    DecisionLog e = entity(id);

    DecisionLogDto dto = mapper.toDto(e);

    assertThat(dto).isNotNull();
    assertThat(dto.decisionId()).isEqualTo(e.getDecisionId());
    assertThat(dto.traceId()).isEqualTo(e.getTraceId());
    assertThat(dto.parentDecisionId()).isEqualTo(e.getParentDecisionId());
    assertThat(dto.scopeKind()).isEqualTo("RECIPE");
    assertThat(dto.scopeId()).isEqualTo(e.getScopeId());
    assertThat(dto.scale()).isEqualTo(DecisionLogScale.RECIPE);
    assertThat(dto.triggeredBy()).isEqualTo("trigger");
    assertThat(dto.actorUserId()).isEqualTo(e.getActorUserId());
    assertThat(dto.inputs()).isEqualTo(e.getInputs());
    assertThat(dto.candidates()).isEqualTo(e.getCandidates());
    assertThat(dto.chosen()).isEqualTo(e.getChosen());
    assertThat(dto.reasoning()).isEqualTo("because");
    assertThat(dto.emittedDirective()).isEqualTo(e.getEmittedDirective());
    assertThat(dto.iteration()).isEqualTo(3);
    assertThat(dto.durationMs()).isEqualTo(42);
  }

  @Test
  void toDtos_null_list_returns_null() {
    assertThat(mapper.toDtos(null)).isNull();
  }

  @Test
  void toDtos_empty_list_returns_empty_not_null() {
    assertThat(mapper.toDtos(List.of())).isEmpty();
  }

  @Test
  void toDtos_maps_each_element_preserving_order() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    List<DecisionLogDto> dtos = mapper.toDtos(List.of(entity(a), entity(b)));

    assertThat(dtos).hasSize(2);
    assertThat(dtos.get(0).decisionId()).isEqualTo(a);
    assertThat(dtos.get(1).decisionId()).isEqualTo(b);
  }
}
