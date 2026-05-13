package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.AdaptationFingerprint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AdaptationFingerprint}. {@code public} so the in-module {@code
 * domain.service} package can inject it; cross-module isolation comes from the (01f-shipping)
 * {@code ModuleBoundaryArchTest}. Verbatim from {@code lld/adaptation-pipeline.md} lines 454-458.
 */
public interface AdaptationFingerprintRepository
    extends JpaRepository<AdaptationFingerprint, UUID> {

  Optional<AdaptationFingerprint> findByRecipeIdAndBranchId(UUID recipeId, UUID branchId);

  Optional<AdaptationFingerprint> findByVersionId(UUID versionId);

  Optional<AdaptationFingerprint> findByBodyHash(String bodyHash);
}
