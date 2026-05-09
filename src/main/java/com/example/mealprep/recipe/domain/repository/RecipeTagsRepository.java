package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeTags;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RecipeTags}. */
public interface RecipeTagsRepository extends JpaRepository<RecipeTags, UUID> {}
