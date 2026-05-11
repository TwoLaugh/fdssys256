package com.example.mealprep.nutrition.domain.repository;

import com.example.mealprep.nutrition.domain.entity.IngredientMapping;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IngredientMapping}. Verbatim from LLD lines 638-644:
 * single-term cache lookup, batch lookup (drives cross-module {@code lookupIngredients}),
 * needs-review paging, and the cache LIKE search.
 */
public interface IngredientMappingRepository extends JpaRepository<IngredientMapping, UUID> {

  Optional<IngredientMapping> findBySearchTerm(String searchTerm);

  List<IngredientMapping> findBySearchTermIn(Collection<String> searchTerms);

  Page<IngredientMapping> findByNeedsReviewTrueOrderByUpdatedAtDesc(Pageable pageable);

  @Query(
      "select im from IngredientMapping im "
          + "where lower(im.searchTerm) like concat('%', lower(:q), '%')")
  Page<IngredientMapping> searchByTerm(@Param("q") String query, Pageable pageable);
}
