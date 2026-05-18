package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.RecipeBranchDto;
import com.example.mealprep.recipe.api.mapper.RecipeBranchMapper;
import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeBranch;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeBranchMapper}: null-entity guard, the {@code recipe == null}
 * id projection, the createdAt-ascending sort (main-first contract), and the null/empty source
 * short-circuit. Real instance, no mocking.
 */
class RecipeBranchMapperTest {

  private final RecipeBranchMapper mapper = new RecipeBranchMapper();

  @Test
  void toDto_nullEntity_returnsNull() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_projectsAllFields_andRecipeId() {
    UUID recipeId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID parentBranchId = UUID.randomUUID();
    UUID branchPointVersionId = UUID.randomUUID();
    UUID traceId = UUID.randomUUID();
    Instant created = Instant.now();
    RecipeBranch branch =
        RecipeBranch.builder()
            .id(id)
            .recipe(Recipe.builder().id(recipeId).build())
            .parentBranchId(parentBranchId)
            .branchPointVersionId(branchPointVersionId)
            .name("gluten-free-variant")
            .label("Gluten free")
            .reason("Dietary fork")
            .currentVersion(3)
            .divergenceScore(new BigDecimal("0.250"))
            .createdAt(created)
            .createdByActor("user:abc")
            .adapterTraceId(traceId)
            .version(5L)
            .build();

    RecipeBranchDto dto = mapper.toDto(branch);

    assertThat(dto.id()).isEqualTo(id);
    assertThat(dto.recipeId()).isEqualTo(recipeId);
    assertThat(dto.parentBranchId()).isEqualTo(parentBranchId);
    assertThat(dto.branchPointVersionId()).isEqualTo(branchPointVersionId);
    assertThat(dto.name()).isEqualTo("gluten-free-variant");
    assertThat(dto.label()).isEqualTo("Gluten free");
    assertThat(dto.reason()).isEqualTo("Dietary fork");
    assertThat(dto.currentVersion()).isEqualTo(3);
    assertThat(dto.divergenceScore()).isEqualByComparingTo("0.250");
    assertThat(dto.createdAt()).isEqualTo(created);
    assertThat(dto.createdByActor()).isEqualTo("user:abc");
    assertThat(dto.adapterTraceId()).isEqualTo(traceId);
    assertThat(dto.version()).isEqualTo(5L);
  }

  @Test
  void toDto_nullRecipe_yieldsNullRecipeId() {
    RecipeBranch branch =
        RecipeBranch.builder().id(UUID.randomUUID()).name("main").createdAt(Instant.now()).build();

    assertThat(mapper.toDto(branch).recipeId()).isNull();
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
  void toDtoList_sortsByCreatedAtAscending_doesNotMutateInput() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    RecipeBranch main = branch("main", t0);
    RecipeBranch mid = branch("mid", t0.plusSeconds(100));
    RecipeBranch late = branch("late", t0.plusSeconds(200));

    // Supplied out of order; expect createdAt-ascending → main, mid, late.
    List<RecipeBranch> input = new java.util.ArrayList<>(List.of(late, main, mid));
    List<RecipeBranchDto> out = mapper.toDtoList(input);

    assertThat(out).extracting(RecipeBranchDto::name).containsExactly("main", "mid", "late");
    // Input list ordering must be untouched (mapper copies before sorting).
    assertThat(input).extracting(RecipeBranch::getName).containsExactly("late", "main", "mid");
  }

  private static RecipeBranch branch(String name, Instant createdAt) {
    return RecipeBranch.builder()
        .id(UUID.randomUUID())
        .recipe(Recipe.builder().id(UUID.randomUUID()).build())
        .name(name)
        .currentVersion(1)
        .divergenceScore(new BigDecimal("0.000"))
        .createdAt(createdAt)
        .createdByActor("user:test")
        .version(0L)
        .build();
  }
}
