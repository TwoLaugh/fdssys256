package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.RecipeVersionDto;
import com.example.mealprep.recipe.api.mapper.IngredientMapper;
import com.example.mealprep.recipe.api.mapper.MethodStepMapper;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.api.mapper.RecipeVersionMapper;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.entity.VersionTrigger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeVersionMapper}: the null-entity guards on both {@code toDto}
 * and {@code toOverlayDto}, the branch-null projection, the {@code Boolean.TRUE.equals} optional
 * mapping, and the null overlaid-list guards. Real sibling mappers (no mocking within module).
 */
class RecipeVersionMapperTest {

  private final RecipeVersionMapper mapper =
      new RecipeVersionMapper(
          new IngredientMapper(),
          new MethodStepMapper(),
          new RecipeMetadataMapper(),
          new RecipeTagsMapper());

  @Test
  void toDto_nullEntity_returnsNull() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_nullEntityWithSubs_returnsNull() {
    assertThat(mapper.toDto(null, List.of(UUID.randomUUID()))).isNull();
  }

  @Test
  void toDto_singleArg_setsAppliedSubsNull_andCopiesScalars() {
    UUID branchId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    RecipeVersion v = bareVersion(branchId);
    v.setParentVersionId(parentId);
    v.setTrigger(VersionTrigger.MANUAL_CREATE);
    v.setChangeReason("first version");
    v.setEmbeddingStatus("pending");
    v.setCreatedByActor("user:abc");
    v.setAdapterTraceId(traceId);

    RecipeVersionDto dto = mapper.toDto(v);

    assertThat(dto.id()).isEqualTo(v.getId());
    assertThat(dto.branchId()).isEqualTo(branchId);
    assertThat(dto.versionNumber()).isEqualTo(1);
    assertThat(dto.parentVersionId()).isEqualTo(parentId);
    assertThat(dto.trigger()).isEqualTo(VersionTrigger.MANUAL_CREATE);
    assertThat(dto.changeReason()).isEqualTo("first version");
    assertThat(dto.embeddingStatus()).isEqualTo("pending");
    assertThat(dto.createdByActor()).isEqualTo("user:abc");
    assertThat(dto.adapterTraceId()).isEqualTo(traceId);
    assertThat(dto.appliedSubstitutionIds()).isNull();
    assertThat(dto.ingredients()).isEmpty();
    assertThat(dto.methodSteps()).isEmpty();
  }

  @Test
  void toDto_nullBranch_yieldsNullBranchId() {
    RecipeVersion v = bareVersion(UUID.randomUUID());
    v.setBranch(null);

    assertThat(mapper.toDto(v).branchId()).isNull();
  }

  @Test
  void toDto_appliedSubsPropagated() {
    List<UUID> subs = List.of(UUID.randomUUID(), UUID.randomUUID());
    RecipeVersion v = bareVersion(UUID.randomUUID());

    assertThat(mapper.toDto(v, subs).appliedSubstitutionIds()).isEqualTo(subs);
  }

  // ---------------- toOverlayDto ----------------

  @Test
  void toOverlayDto_nullBaseVersion_returnsNull() {
    assertThat(mapper.toOverlayDto(null, List.of(), List.of(), List.of())).isNull();
  }

  @Test
  void toOverlayDto_mapsOverlaidIngredients_optionalTrueEquality() {
    UUID branchId = UUID.randomUUID();
    RecipeVersion base = bareVersion(branchId);
    List<CreateIngredientRequest> overlaid =
        List.of(
            new CreateIngredientRequest(
                0, "soy.crumble", "Soy crumble", new BigDecimal("400.000"), "g", "diced", true),
            new CreateIngredientRequest(1, "salt", "Salt", null, null, null, null));
    List<CreateMethodStepRequest> method =
        List.of(new CreateMethodStepRequest(1, "Brown the crumble.", 5));
    List<UUID> subs = List.of(UUID.randomUUID());

    RecipeVersionDto dto = mapper.toOverlayDto(base, overlaid, method, subs);

    assertThat(dto.id()).isEqualTo(base.getId());
    assertThat(dto.branchId()).isEqualTo(branchId);
    assertThat(dto.ingredients()).hasSize(2);
    assertThat(dto.ingredients().get(0).displayName()).isEqualTo("Soy crumble");
    assertThat(dto.ingredients().get(0).optional()).isTrue();
    // null optional must map to false via Boolean.TRUE.equals.
    assertThat(dto.ingredients().get(1).optional()).isFalse();
    assertThat(dto.ingredients().get(0).id()).isNull();
    assertThat(dto.ingredients().get(0).needsReview()).isFalse();
    assertThat(dto.methodSteps()).hasSize(1);
    assertThat(dto.methodSteps().get(0).stepNumber()).isEqualTo(1);
    assertThat(dto.methodSteps().get(0).instruction()).isEqualTo("Brown the crumble.");
    assertThat(dto.methodSteps().get(0).durationMinutes()).isEqualTo(5);
    assertThat(dto.appliedSubstitutionIds()).isEqualTo(subs);
  }

  @Test
  void toOverlayDto_optionalFalse_mapsToFalse() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    List<CreateIngredientRequest> overlaid =
        List.of(new CreateIngredientRequest(0, "k", "d", null, null, null, false));

    RecipeVersionDto dto = mapper.toOverlayDto(base, overlaid, List.of(), List.of());

    assertThat(dto.ingredients().get(0).optional()).isFalse();
  }

  @Test
  void toOverlayDto_nullOverlaidLists_yieldEmptyCollections() {
    RecipeVersion base = bareVersion(UUID.randomUUID());

    RecipeVersionDto dto = mapper.toOverlayDto(base, null, null, null);

    assertThat(dto.ingredients()).isEmpty();
    assertThat(dto.methodSteps()).isEmpty();
    assertThat(dto.appliedSubstitutionIds()).isNull();
  }

  @Test
  void toOverlayDto_nullBranch_yieldsNullBranchId() {
    RecipeVersion base = bareVersion(UUID.randomUUID());
    base.setBranch(null);

    assertThat(mapper.toOverlayDto(base, List.of(), List.of(), List.of()).branchId()).isNull();
  }

  // ---------------- helpers ----------------

  private static RecipeVersion bareVersion(UUID branchId) {
    return RecipeVersion.builder()
        .id(UUID.randomUUID())
        .branch(RecipeBranch.builder().id(branchId).name("main").build())
        .versionNumber(1)
        .trigger(VersionTrigger.MANUAL_CREATE)
        .embeddingStatus("pending")
        .createdByActor("user:test")
        .ingredients(new ArrayList<>())
        .methodSteps(new ArrayList<>())
        .build();
  }
}
