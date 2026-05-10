package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.IntakeDay;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link IntakeDay}. Cross-module callers go through {@code
 * NutritionQueryService} / {@code NutritionUpdateService} — enforced by {@code
 * NutritionBoundaryTest}.
 *
 * <p>Notably no multi-attribute {@code @EntityGraph} — the aggregate has three list children
 * ({@code slots}, {@code snacks}, {@code auditLog}) and Hibernate throws {@code
 * MultipleBagFetchException} when more than one is fetched eagerly. Service touches each collection
 * inside {@code @Transactional} to force lazy load.
 */
public interface IntakeDayRepository extends JpaRepository<IntakeDay, UUID> {

  Optional<IntakeDay> findByUserIdAndOnDate(UUID userId, LocalDate onDate);

  List<IntakeDay> findByUserIdAndOnDateBetween(UUID userId, LocalDate from, LocalDate to);
}
