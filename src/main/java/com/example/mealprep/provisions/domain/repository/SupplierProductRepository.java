package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import java.time.LocalDate;
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
 * Spring Data repository for {@link SupplierProduct}. Cross-module callers go through {@code
 * ProvisionQueryService} / {@code ProvisionUpdateService} — enforced by {@code
 * ProvisionsBoundaryTest} (ArchUnit). {@code public} for the same reason as the sibling repos — the
 * in-module {@code domain.service.internal} package needs to inject it; the boundary test, not Java
 * visibility, fences cross-module reach-through.
 */
public interface SupplierProductRepository extends JpaRepository<SupplierProduct, UUID> {

  Optional<SupplierProduct> findBySupplierAndProductId(String supplier, String productId);

  List<SupplierProduct> findAllByIngredientMappingKeyIn(Collection<String> mappingKeys);

  Page<SupplierProduct> findAllByLastCheckedBefore(LocalDate cutoff, Pageable p);

  /**
   * Search with two optional filters — when both are null, every row matches. Driven by the
   * controller's {@code GET /supplier-products?mappingKey=&supplier=} endpoint.
   */
  @Query(
      "select sp from SupplierProduct sp"
          + " where (:mappingKey is null or sp.ingredientMappingKey = :mappingKey)"
          + "   and (:supplier is null or sp.supplier = :supplier)")
  Page<SupplierProduct> search(
      @Param("mappingKey") String mappingKey, @Param("supplier") String supplier, Pageable p);
}
