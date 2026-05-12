package com.example.mealprep.recipe.domain.service.internal;

import com.example.mealprep.recipe.domain.entity.Recipe;
import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import com.example.mealprep.recipe.domain.entity.RecipeTags;
import com.example.mealprep.recipe.domain.entity.RecipeVersion;
import com.example.mealprep.recipe.domain.repository.RecipeVersionRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the deterministic text input the {@code OpenAI text-embedding-3-small} model embeds for
 * a {@link RecipeVersion}. Per LLD line 132: {@code name + description + cuisine + protein +
 * cooking_method + flavour_profile + ingredient names}.
 *
 * <p>The async listener ({@link RecipeEmbeddingListener}) calls {@link #loadAndCompose(UUID)} as
 * its first JPA touch in a fresh {@code @Async} thread. Because the {@code @Async} thread has no
 * inherited transaction, the method opens its own {@code REQUIRES_NEW readOnly} tx so the lazy
 * collection accesses on {@code RecipeVersion} (ingredients) succeed.
 *
 * <p>Deterministic: the same {@link RecipeVersion} always produces a byte-identical input string —
 * ingredient ordering follows the DB {@code line_order} (already enforced by the {@code unique
 * (version_id, line_order)} constraint shipped in 01a's child-list migration), and {@code
 * flavourProfile} iteration order matches its persisted JSON ordering.
 */
@Component
public class RecipeEmbeddingInputBuilder {

  private final RecipeVersionRepository versionRepository;

  public RecipeEmbeddingInputBuilder(RecipeVersionRepository versionRepository) {
    this.versionRepository = versionRepository;
  }

  /**
   * Load the version + its parent recipe + metadata + tags + ingredients and compose the embedding
   * input string. Returns {@code null} if the version row has been hard-deleted between the
   * publisher commit and the listener execution.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public String loadAndCompose(UUID versionId) {
    RecipeVersion v = versionRepository.findById(versionId).orElse(null);
    if (v == null) {
      return null;
    }
    Recipe r = v.getRecipe();
    RecipeMetadata md = v.getMetadata();
    RecipeTags tags = v.getTags();
    List<RecipeIngredient> ingredients = v.getIngredients();

    StringBuilder sb = new StringBuilder(256);
    if (r != null && r.getName() != null) {
      sb.append(r.getName());
    }
    if (r != null && r.getDescription() != null && !r.getDescription().isBlank()) {
      sb.append(' ').append(r.getDescription());
    }
    if (md != null && md.getCuisine() != null && !md.getCuisine().isBlank()) {
      sb.append(' ').append(md.getCuisine());
    }
    if (tags != null) {
      if (tags.getProtein() != null && !tags.getProtein().isBlank()) {
        sb.append(' ').append(tags.getProtein());
      }
      if (tags.getCookingMethod() != null && !tags.getCookingMethod().isBlank()) {
        sb.append(' ').append(tags.getCookingMethod());
      }
      if (tags.getFlavourProfile() != null && !tags.getFlavourProfile().isEmpty()) {
        sb.append(' ').append(String.join(",", tags.getFlavourProfile()));
      }
    }
    if (ingredients != null && !ingredients.isEmpty()) {
      sb.append(' ')
          .append(
              ingredients.stream()
                  .sorted((a, b) -> Integer.compare(a.getLineOrder(), b.getLineOrder()))
                  .map(RecipeIngredient::getDisplayName)
                  .filter(s -> s != null && !s.isBlank())
                  .collect(Collectors.joining(",")));
    }
    return sb.toString().trim().replaceAll("\\s+", " ");
  }
}
