package com.example.mealprep.recipe.domain.repository;

import com.example.mealprep.recipe.domain.entity.RecipeMetadata;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for {@link RecipeMetadata}. */
public interface RecipeMetadataRepository extends JpaRepository<RecipeMetadata, UUID> {}
