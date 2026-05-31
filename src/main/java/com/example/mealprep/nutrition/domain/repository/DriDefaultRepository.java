package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.DriDefault;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@code nutrition_dri_defaults} seed table. {@code public} for the same reason
 * as the other nutrition repos — cross-module reach-through is fenced by {@code
 * NutritionBoundaryTest}, not Java visibility. Used by {@code initialiseTargets} (nutrition-7) to
 * seed DRI micro defaults at onboarding.
 */
public interface DriDefaultRepository extends JpaRepository<DriDefault, UUID> {

  /** All DRI defaults for one {@code (age_group, sex)} band, one row per micronutrient. */
  List<DriDefault> findByAgeGroupAndSex(String ageGroup, String sex);
}
