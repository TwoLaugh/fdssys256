package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeIngredient;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RecipeIngredient}. */
public interface RecipeIngredientRepository extends JpaRepository<RecipeIngredient, UUID> {}
