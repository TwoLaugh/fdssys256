package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.DriDefault;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read access to the {@code nutrition_dri_defaults} seed table. {@code public} for the same reason
 * as the other nutrition repos — cross-module reach-through is fenced by {@code
 * NutritionBoundaryTest}, not Java visibility. Used by {@code initialiseTargets} (nutrition-7) to
 * seed DRI micro defaults at onboarding.
 */
public interface DriDefaultRepository extends JpaRepository<DriDefault, UUID> {

  /**
   * All DRI defaults for one {@code (age_group, sex)} band at the default (non-pregnant,
   * non-lactating) life-stage — one row per micronutrient. Explicitly pinned to {@code life_stage =
   * 'NONE'} so the table's pregnancy/lactation variants never duplicate a key for callers that
   * don't care about life-stage.
   */
  @Query(
      "select d from DriDefault d where d.ageGroup = :ageGroup and d.sex = :sex and d.lifeStage ="
          + " 'NONE'")
  List<DriDefault> findByAgeGroupAndSex(
      @Param("ageGroup") String ageGroup, @Param("sex") String sex);

  /**
   * All DRI defaults for one {@code (age_group, sex, life_stage)} band, one row per micronutrient.
   */
  List<DriDefault> findByAgeGroupAndSexAndLifeStage(String ageGroup, String sex, String lifeStage);
}
