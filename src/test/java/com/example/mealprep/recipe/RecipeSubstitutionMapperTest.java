package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.RecipeSubstitutionDto;
import com.example.mealprep.recipe.api.dto.SubstitutionReason;
import com.example.mealprep.recipe.api.dto.SubstitutionState;
import com.example.mealprep.recipe.api.mapper.RecipeSubstitutionMapper;
import com.example.mealprep.recipe.domain.entity.MethodOverlayLine;
import com.example.mealprep.recipe.domain.entity.RecipeSubstitution;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeSubstitutionMapper}: null-entity guard, the empty/null source
 * list short-circuit, the null vs populated method-overlay branch, and full scalar projection. Real
 * instance, no mocking.
 */
class RecipeSubstitutionMapperTest {

  private final RecipeSubstitutionMapper mapper = new RecipeSubstitutionMapper();

  @Test
  void toDto_nullEntity_returnsNull() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_projectsAllScalars_andSplitsOriginalSubstituteItems() {
    UUID id = UUID.randomUUID();
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    UUID branchId = UUID.randomUUID();
    UUID promotedTo = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant created = Instant.now();
    Instant lastApplied = created.plusSeconds(60);

    RecipeSubstitution sub =
        RecipeSubstitution.builder()
            .id(id)
            .recipeId(recipeId)
            .versionId(versionId)
            .branchId(branchId)
            .originalMappingKey("beef.mince")
            .originalQuantity(new BigDecimal("500.000"))
            .originalUnit("g")
            .substituteMappingKey("soy.crumble")
            .substituteQuantity(new BigDecimal("400.000"))
            .substituteUnit("g")
            .reason(SubstitutionReason.DIETARY_TEMP)
            .constraintRef("constraint:vegan")
            .methodOverlay(List.of(new MethodOverlayLine(2, "Brown the crumble.")))
            .notes("temporary swap")
            .temporary(true)
            .applicationCount(3)
            .lastAppliedAt(lastApplied)
            .state(SubstitutionState.PROPOSED)
            .promotedToVersionId(promotedTo)
            .createdAt(created)
            .createdByActor("user:abc")
            .adapterTraceId(traceId)
            .version(7L)
            .build();

    RecipeSubstitutionDto dto = mapper.toDto(sub);

    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.recipeId()).isEqualTo(recipeId);
    assertThat(dto.versionId()).isEqualTo(versionId);
    assertThat(dto.branchId()).isEqualTo(branchId);
    assertThat(dto.original().ingredientMappingKey()).isEqualTo("beef.mince");
    assertThat(dto.original().quantity()).isEqualByComparingTo("500.000");
    assertThat(dto.original().unit()).isEqualTo("g");
    assertThat(dto.substitute().ingredientMappingKey()).isEqualTo("soy.crumble");
    assertThat(dto.substitute().quantity()).isEqualByComparingTo("400.000");
    assertThat(dto.reason()).isEqualTo(SubstitutionReason.DIETARY_TEMP);
    assertThat(dto.constraintRef()).isEqualTo("constraint:vegan");
    assertThat(dto.methodOverlay()).hasSize(1);
    assertThat(dto.methodOverlay().get(0).step()).isEqualTo(2);
    assertThat(dto.methodOverlay().get(0).instruction()).isEqualTo("Brown the crumble.");
    assertThat(dto.notes()).isEqualTo("temporary swap");
    assertThat(dto.temporary()).isTrue();
    assertThat(dto.applicationCount()).isEqualTo(3);
    assertThat(dto.lastAppliedAt()).isEqualTo(lastApplied);
    assertThat(dto.state()).isEqualTo(SubstitutionState.PROPOSED);
    assertThat(dto.promotedToVersionId()).isEqualTo(promotedTo);
    assertThat(dto.createdAt()).isEqualTo(created);
    assertThat(dto.createdByActor()).isEqualTo("user:abc");
    assertThat(dto.adapterTraceId()).isEqualTo(traceId);
    assertThat(dto.version()).isEqualTo(7L);
  }

  @Test
  void toDto_nullMethodOverlay_mapsToNullList() {
    RecipeSubstitution sub = minimal().methodOverlay(null).build();
    assertThat(mapper.toDto(sub).methodOverlay()).isNull();
  }

  @Test
  void toDto_emptyMethodOverlay_mapsToEmptyList() {
    RecipeSubstitution sub = minimal().methodOverlay(List.of()).build();
    assertThat(mapper.toDto(sub).methodOverlay()).isEmpty();
  }

  @Test
  void toDtoList_nullSource_returnsEmptyList() {
    assertThat(mapper.toDtoList(null)).isEmpty();
  }

  @Test
  void toDtoList_emptySource_returnsEmptyList() {
    assertThat(mapper.toDtoList(List.of())).isEmpty();
  }

  @Test
  void toDtoList_mapsEachEntity_preservingOrder() {
    RecipeSubstitution a = minimal().notes("first").build();
    RecipeSubstitution b = minimal().notes("second").build();

    List<RecipeSubstitutionDto> out = mapper.toDtoList(List.of(a, b));

    assertThat(out).hasSize(2);
    assertThat(out.get(0).notes()).isEqualTo("first");
    assertThat(out.get(1).notes()).isEqualTo("second");
  }

  private static RecipeSubstitution.RecipeSubstitutionBuilder minimal() {
    return RecipeSubstitution.builder()
        .id(UUID.randomUUID())
        .recipeId(UUID.randomUUID())
        .versionId(UUID.randomUUID())
        .branchId(UUID.randomUUID())
        .originalMappingKey("beef.mince")
        .originalQuantity(new BigDecimal("500.000"))
        .originalUnit("g")
        .substituteMappingKey("soy.crumble")
        .substituteQuantity(new BigDecimal("400.000"))
        .substituteUnit("g")
        .reason(SubstitutionReason.DIETARY_TEMP)
        .temporary(true)
        .applicationCount(0)
        .state(SubstitutionState.PROPOSED)
        .createdAt(Instant.now())
        .createdByActor("user:test")
        .version(0L);
  }
}
