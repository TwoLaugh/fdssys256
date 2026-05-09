package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.AllergenDerivative;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookup over the {@code preference_allergen_derivatives} reference table. Package-private at the
 * package level — cross-module callers go through {@code HardConstraintFilterService}.
 */
public interface AllergenDerivativeRepository extends JpaRepository<AllergenDerivative, UUID> {

  /**
   * Returns every derivative key registered for any of the supplied allergens. The filter service
   * calls this once per check to expand the user's stored allergies into the full match set.
   */
  @Query("select ad.derivative from AllergenDerivative ad where ad.allergen in :allergens")
  Set<String> findDerivativesForAllergens(@Param("allergens") Collection<String> allergens);
}
