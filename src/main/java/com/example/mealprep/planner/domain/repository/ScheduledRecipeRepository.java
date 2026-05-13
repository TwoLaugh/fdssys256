package com.example.mealprep.planner.domain.repository;

import com.example.mealprep.planner.domain.entity.ScheduledRecipe;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Package-private repository for {@link ScheduledRecipe}. Empty in 01a; the recipe-deletion guard
 * ({@code existsActiveScheduleForRecipe}) lands with the recipe-module ticket that adds that guard
 * (out of scope for the planner module).
 */
public interface ScheduledRecipeRepository extends JpaRepository<ScheduledRecipe, UUID> {}
