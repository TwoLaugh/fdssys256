package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.AdaptationFingerprint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AdaptationFingerprint}. Package-private per LLD line 414.
 * Verbatim from {@code lld/adaptation-pipeline.md} lines 454-458.
 */
interface AdaptationFingerprintRepository extends JpaRepository<AdaptationFingerprint, UUID> {

  Optional<AdaptationFingerprint> findByRecipeIdAndBranchId(UUID recipeId, UUID branchId);

  Optional<AdaptationFingerprint> findByVersionId(UUID versionId);

  Optional<AdaptationFingerprint> findByBodyHash(String bodyHash);
}
