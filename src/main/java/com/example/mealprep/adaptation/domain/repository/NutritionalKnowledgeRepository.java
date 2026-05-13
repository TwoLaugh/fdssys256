package com.example.mealprep.adaptation.domain.repository;

import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link NutritionalKnowledgeEntry}. Package-private per LLD line 414.
 * Verbatim from {@code lld/adaptation-pipeline.md} lines 470-476.
 *
 * <p>The native intersect on {@code subject_keys && cast(:keys as text[])} exploits the GIN index
 * declared in {@code V20260615120500__adaptation_create_nutritional_knowledge.sql}. The explicit
 * cast is required because Hibernate's {@code String[]} parameter binding doesn't carry array-type
 * info Postgres can use for the {@code &&} operator otherwise.
 */
interface NutritionalKnowledgeRepository extends JpaRepository<NutritionalKnowledgeEntry, UUID> {

  @Query(
      value =
          "select * from adaptation_nutritional_knowledge "
              + "where knowledge_kind = :kind and subject_keys && cast(:keys as text[])",
      nativeQuery = true)
  List<NutritionalKnowledgeEntry> findIntersectingSubjects(
      @Param("kind") String kind, @Param("keys") String[] subjectKeys);
}
