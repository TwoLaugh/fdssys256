package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.NutritionDivergenceState;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link NutritionDivergenceState}. Cross-module callers go through
 * {@code NutritionQueryService} / {@code NutritionUpdateService} — enforced by {@code
 * NutritionBoundaryTest}.
 *
 * <p>The interface is {@code public} only because the in-module {@code domain.service.internal}
 * package needs to inject it; package-private would prevent any reference from another package,
 * including same-module ones.
 */
public interface NutritionDivergenceStateRepository
    extends JpaRepository<NutritionDivergenceState, NutritionDivergenceState.Key> {

  Optional<NutritionDivergenceState> findByUserIdAndOnDate(UUID userId, LocalDate onDate);
}
