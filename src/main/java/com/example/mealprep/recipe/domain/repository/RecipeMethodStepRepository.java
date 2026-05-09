package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeMethodStep;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RecipeMethodStep}. */
public interface RecipeMethodStepRepository extends JpaRepository<RecipeMethodStep, UUID> {}
