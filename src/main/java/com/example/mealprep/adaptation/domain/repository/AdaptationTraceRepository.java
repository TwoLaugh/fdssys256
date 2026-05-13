package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.AdaptationTrace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AdaptationTrace}. Package-private per LLD line 414. Verbatim
 * from {@code lld/adaptation-pipeline.md} lines 447-452.
 */
interface AdaptationTraceRepository extends JpaRepository<AdaptationTrace, UUID> {

  Optional<AdaptationTrace> findByJobId(UUID jobId);

  Page<AdaptationTrace> findByRecipeIdOrderByCreatedAtDesc(UUID recipeId, Pageable p);

  Page<AdaptationTrace> findByPromptTemplateNameAndPromptTemplateVersionOrderByCreatedAtDesc(
      String name, String version, Pageable p);
}
