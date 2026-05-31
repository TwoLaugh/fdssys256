package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.mealprep.recipe.api.dto.CreateIngredientRequest;
import com.example.mealprep.recipe.api.dto.CreateMethodStepRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeMetadataRequest;
import com.example.mealprep.recipe.api.dto.CreateRecipeRequest;
import com.example.mealprep.recipe.domain.repository.RecipeRepository;
import com.example.mealprep.recipe.domain.service.internal.RecipeDeduplicationService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@link RecipeDeduplicationService} (recipe-2). Verifies the HLD §Recipe
 * deduplication contract: normalised ingredient-set hash, ≥80% Jaccard overlap + ±20% method length
 * gate, candidate carries the id + measured overlap.
 */
@ExtendWith(MockitoExtension.class)
class RecipeDeduplicationServiceTest {

  @Mock private RecipeRepository recipeRepository;

  private RecipeDeduplicationService service() {
    return new RecipeDeduplicationService(recipeRepository);
  }

  private static CreateRecipeRequest recipe(List<String> keys, int methodSteps) {
    List<CreateIngredientRequest> ingredients = new ArrayList<>();
    int order = 0;
    for (String key : keys) {
      ingredients.add(
          new CreateIngredientRequest(
              order++, key, key, new BigDecimal("1.000"), "g", null, false));
    }
    List<CreateMethodStepRequest> method = new ArrayList<>();
    for (int i = 1; i <= methodSteps; i++) {
      method.add(new CreateMethodStepRequest(i, "step " + i, null));
    }
    CreateRecipeMetadataRequest metadata =
        new CreateRecipeMetadataRequest(2, 0, 0, 0, List.of(), null, null, false, null, List.of());
    return new CreateRecipeRequest("R", null, ingredients, method, metadata, null);
  }

  /** A candidate row tuple {recipeId, mappingKey, methodStepCount}. */
  private static Object[] row(UUID recipeId, String key, long methodSteps) {
    return new Object[] {recipeId, key, methodSteps};
  }

  @Test
  void ingredientSetHash_isOrderAndQuantityIndependent() {
    RecipeDeduplicationService svc = service();
    String h1 = svc.ingredientSetHash(recipe(List.of("a.b", "c.d", "e.f"), 0).ingredients());
    String h2 = svc.ingredientSetHash(recipe(List.of("e.f", "a.b", "c.d"), 0).ingredients());
    assertThat(h1).isNotNull().isEqualTo(h2);
  }

  @Test
  void ingredientSetHash_null_whenNoUsableKeys() {
    assertThat(service().ingredientSetHash(List.of())).isNull();
  }

  @Test
  void findDuplicate_identicalSet_sameMethodLength_flagsCandidate() {
    UUID userId = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(
            List.of(
                row(candidate, "chicken.breast", 3L),
                row(candidate, "soy.sauce", 3L),
                row(candidate, "ginger.fresh", 3L)));

    Optional<RecipeDeduplicationService.DuplicateMatch> match =
        service()
            .findDuplicate(
                userId, recipe(List.of("chicken.breast", "soy.sauce", "ginger.fresh"), 3));

    assertThat(match).isPresent();
    assertThat(match.get().candidateRecipeId()).isEqualTo(candidate);
    assertThat(match.get().ingredientOverlap()).isEqualTo(1.0);
  }

  @Test
  void findDuplicate_fourOfFiveOverlap_aboveThreshold_flags() {
    // incoming {a,b,c,d}; candidate {a,b,c,d} plus the candidate also has nothing extra → 100%.
    // Use 4/5 overlap to land at 0.8 exactly (>= threshold): incoming {a,b,c,d,e}; candidate
    // {a,b,c,d}. union=5, intersection=4 → 0.8.
    UUID userId = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(
            List.of(
                row(candidate, "a", 4L),
                row(candidate, "b", 4L),
                row(candidate, "c", 4L),
                row(candidate, "d", 4L)));

    Optional<RecipeDeduplicationService.DuplicateMatch> match =
        service().findDuplicate(userId, recipe(List.of("a", "b", "c", "d", "e"), 4));

    assertThat(match).isPresent();
    assertThat(match.get().ingredientOverlap()).isEqualTo(0.8);
  }

  @Test
  void findDuplicate_belowOverlapThreshold_allowed() {
    // incoming {a,b,c,d,e,f}; candidate {a,b,c} → intersection 3, union 6 → 0.5 < 0.8.
    UUID userId = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(
            List.of(row(candidate, "a", 5L), row(candidate, "b", 5L), row(candidate, "c", 5L)));

    Optional<RecipeDeduplicationService.DuplicateMatch> match =
        service().findDuplicate(userId, recipe(List.of("a", "b", "c", "d", "e", "f"), 5));

    assertThat(match).isEmpty();
  }

  @Test
  void findDuplicate_sameIngredients_butMethodLengthOutsideTolerance_allowed() {
    // Identical ingredient set (overlap 1.0) but the candidate has 3 steps vs incoming 10 →
    // |3-10|/10 = 0.7 > 0.20 tolerance → near-but-distinct, allowed.
    UUID userId = UUID.randomUUID();
    UUID candidate = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(
            List.of(row(candidate, "a", 3L), row(candidate, "b", 3L), row(candidate, "c", 3L)));

    Optional<RecipeDeduplicationService.DuplicateMatch> match =
        service().findDuplicate(userId, recipe(List.of("a", "b", "c"), 10));

    assertThat(match).isEmpty();
  }

  @Test
  void findDuplicate_emptyLibrary_allowed() {
    UUID userId = UUID.randomUUID();
    when(recipeRepository.findCurrentVersionIngredientKeysAndMethodCountForUser(userId))
        .thenReturn(List.of());

    assertThat(service().findDuplicate(userId, recipe(List.of("a", "b", "c"), 3))).isEmpty();
  }

  @Test
  void findDuplicate_incomingHasNoKeys_neverFlags() {
    UUID userId = UUID.randomUUID();
    // No repository interaction expected — the incoming key set is empty so we short-circuit.
    assertThat(service().findDuplicate(userId, recipe(List.of(), 3))).isEmpty();
  }
}
