package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.HardIntolerance;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link HardIntolerance} child rows. Package-private at the package
 * level per the module-boundary rules — cross-module callers go through {@code
 * PreferenceQueryService} / {@code PreferenceUpdateService}.
 *
 * <p>Added in nutrition/01j to (a) stamp directive provenance on a freshly-added temporary
 * intolerance and (b) reverse directive-sourced rows in {@code
 * PreferenceUpdateService.removeTemporaryConstraint}.
 */
public interface HardIntoleranceRepository extends JpaRepository<HardIntolerance, UUID> {

  /** Directive-sourced rows for a given source directive (used by the expiry reversal). */
  List<HardIntolerance> findBySourceDirectiveId(UUID sourceDirectiveId);

  /**
   * Intolerances for an aggregate matching {@code substance} that are not yet directive-stamped —
   * used to locate the row a temporary directive just added so its provenance can be stamped.
   */
  List<HardIntolerance> findByHardConstraintsIdAndSubstanceAndSourceDirectiveIdIsNull(
      UUID hardConstraintsId, String substance);
}
