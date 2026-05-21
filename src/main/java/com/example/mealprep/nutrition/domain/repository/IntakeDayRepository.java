package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import com.example.mealprep.nutrition.domain.entity.IntakeSlot;
import com.example.mealprep.nutrition.domain.entity.MealSlot;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IntakeDay}. Cross-module callers go through {@code
 * NutritionQueryService} / {@code NutritionUpdateService} — enforced by {@code
 * NutritionBoundaryTest}.
 *
 * <p>Notably no multi-attribute {@code @EntityGraph} — the aggregate has three list children
 * ({@code slots}, {@code snacks}, {@code auditLog}) and Hibernate throws {@code
 * MultipleBagFetchException} when more than one is fetched eagerly. Service touches each collection
 * inside {@code @Transactional} to force lazy load.
 *
 * <p>{@link #searchSlots(UUID, UUID, MealSlot, String, boolean, Pageable)} backs the C-B-048
 * intake-history search endpoint. JPQL composition uses {@code :param IS NULL OR ...} short-circuit
 * idiom rather than {@code JpaSpecificationExecutor} — keeps the search in one place without
 * spreading specifications across the module.
 */
public interface IntakeDayRepository extends JpaRepository<IntakeDay, UUID> {

  Optional<IntakeDay> findByUserIdAndOnDate(UUID userId, LocalDate onDate);

  List<IntakeDay> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);

  /**
   * Paginated search across the user's intake slots, filtered by optional {@code recipeId}, {@code
   * mealSlot}, and case-insensitive substring match on {@code overrideFreeText}.
   *
   * <p>The {@code hasQuery} flag tells the query whether to apply the substring predicate — JPA's
   * named-parameter substitution can't conditionally include a {@code LIKE} clause based on the
   * value alone (an empty pattern would match nothing rather than be ignored).
   *
   * <p>Tenant scoping is enforced inside the JPQL: every row is filtered by {@code intakeDay.userId
   * = :userId}. The caller is expected to pass a {@code Pageable} whose sort includes the stable
   * {@code id} tiebreaker — the service layer enforces this.
   */
  @Query(
      """
      select s from IntakeSlot s
        join s.intakeDay d
       where d.userId = :userId
         and (:recipeId is null or s.plannedRecipeId = :recipeId)
         and (:mealSlot is null or s.mealSlot = :mealSlot)
         and (:hasQuery = false or lower(coalesce(s.overrideFreeText, '')) like lower(concat('%', :q, '%')))
      """)
  Page<IntakeSlot> searchSlots(
      @Param("userId") UUID userId,
      @Param("recipeId") UUID recipeId,
      @Param("mealSlot") MealSlot mealSlot,
      @Param("q") String q,
      @Param("hasQuery") boolean hasQuery,
      Pageable pageable);
}
