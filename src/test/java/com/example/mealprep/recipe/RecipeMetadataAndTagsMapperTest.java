package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.RecipeMetadataDto;
import com.example.mealprep.recipe.api.dto.RecipeTagsDto;
import com.example.mealprep.recipe.api.mapper.RecipeMetadataMapper;
import com.example.mealprep.recipe.api.mapper.RecipeTagsMapper;
import com.example.mealprep.recipe.domain.entity.Complexity;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeMetadataMapper} and {@link RecipeTagsMapper}: null-entity
 * guards, the {@code copyOrEmpty} null/empty short-circuit, and the defensive-copy contract (output
 * list must not be the same instance as the entity's). Real instances, no mocking.
 */
class RecipeMetadataAndTagsMapperTest {

  @Nested
  class Metadata {

    private final RecipeMetadataMapper mapper = new RecipeMetadataMapper();

    @Test
    void nullEntity_returnsNull() {
      assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void populatedLists_copiedNotShared() {
      List<String> equipment = new ArrayList<>(List.of("pan", "whisk"));
      List<String> meals = new ArrayList<>(List.of("DINNER"));
      RecipeMetadata md =
          RecipeMetadata.builder()
              .id(UUID.randomUUID())
              .servings(4)
              .prepTimeMins(15)
              .cookTimeMins(30)
              .totalTimeMins(45)
              .equipmentRequired(equipment)
              .fridgeDays(3)
              .freezerWeeks(2)
              .packable(true)
              .cuisine("Italian")
              .mealTypes(meals)
              .build();

      RecipeMetadataDto dto = mapper.toDto(md);

      assertThat(dto.servings()).isEqualTo(4);
      assertThat(dto.prepTimeMins()).isEqualTo(15);
      assertThat(dto.cookTimeMins()).isEqualTo(30);
      assertThat(dto.totalTimeMins()).isEqualTo(45);
      assertThat(dto.fridgeDays()).isEqualTo(3);
      assertThat(dto.freezerWeeks()).isEqualTo(2);
      assertThat(dto.packable()).isTrue();
      assertThat(dto.cuisine()).isEqualTo("Italian");
      assertThat(dto.equipmentRequired()).containsExactly("pan", "whisk").isNotSameAs(equipment);
      assertThat(dto.mealTypes()).containsExactly("DINNER").isNotSameAs(meals);
    }

    @Test
    void nullLists_yieldEmpty_packableFalsePreserved() {
      RecipeMetadata md =
          RecipeMetadata.builder()
              .id(UUID.randomUUID())
              .servings(1)
              .prepTimeMins(0)
              .cookTimeMins(0)
              .totalTimeMins(0)
              .equipmentRequired(null)
              .packable(false)
              .mealTypes(null)
              .build();

      RecipeMetadataDto dto = mapper.toDto(md);

      assertThat(dto.equipmentRequired()).isEmpty();
      assertThat(dto.mealTypes()).isEmpty();
      assertThat(dto.packable()).isFalse();
    }

    @Test
    void emptyLists_yieldEmpty() {
      RecipeMetadata md =
          RecipeMetadata.builder()
              .id(UUID.randomUUID())
              .servings(1)
              .prepTimeMins(0)
              .cookTimeMins(0)
              .totalTimeMins(0)
              .equipmentRequired(new ArrayList<>())
              .packable(false)
              .mealTypes(new ArrayList<>())
              .build();

      assertThat(mapper.toDto(md).equipmentRequired()).isEmpty();
      assertThat(mapper.toDto(md).mealTypes()).isEmpty();
    }
  }

  @Nested
  class Tags {

    private final RecipeTagsMapper mapper = new RecipeTagsMapper();

    @Test
    void nullEntity_returnsNull() {
      assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void populatedLists_copiedNotShared() {
      List<String> flavour = new ArrayList<>(List.of("savoury", "umami"));
      List<String> dietary = new ArrayList<>(List.of("vegan"));
      RecipeTags tags =
          RecipeTags.builder()
              .id(UUID.randomUUID())
              .protein("beef")
              .cookingMethod("stovetop")
              .complexity(Complexity.MODERATE)
              .flavourProfile(flavour)
              .dietaryFlags(dietary)
              .build();

      RecipeTagsDto dto = mapper.toDto(tags);

      assertThat(dto.protein()).isEqualTo("beef");
      assertThat(dto.cookingMethod()).isEqualTo("stovetop");
      assertThat(dto.complexity()).isEqualTo(Complexity.MODERATE);
      assertThat(dto.flavourProfile()).containsExactly("savoury", "umami").isNotSameAs(flavour);
      assertThat(dto.dietaryFlags()).containsExactly("vegan").isNotSameAs(dietary);
    }

    @Test
    void nullLists_yieldEmpty() {
      RecipeTags tags =
          RecipeTags.builder()
              .id(UUID.randomUUID())
              .protein(null)
              .cookingMethod(null)
              .complexity(null)
              .flavourProfile(null)
              .dietaryFlags(null)
              .build();

      RecipeTagsDto dto = mapper.toDto(tags);

      assertThat(dto.flavourProfile()).isEmpty();
      assertThat(dto.dietaryFlags()).isEmpty();
      assertThat(dto.protein()).isNull();
      assertThat(dto.complexity()).isNull();
    }

    @Test
    void emptyLists_yieldEmpty() {
      RecipeTags tags =
          RecipeTags.builder()
              .id(UUID.randomUUID())
              .flavourProfile(new ArrayList<>())
              .dietaryFlags(new ArrayList<>())
              .build();

      assertThat(mapper.toDto(tags).flavourProfile()).isEmpty();
      assertThat(mapper.toDto(tags).dietaryFlags()).isEmpty();
    }
  }
}
