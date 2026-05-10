package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.Budget;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Budget}. Cross-module callers go through {@code
 * ProvisionQueryService} / {@code ProvisionUpdateService} — enforced by {@code
 * ProvisionsBoundaryTest} (ArchUnit). Public visibility for the same reason as the sibling repos —
 * boundary test, not Java visibility, fences cross-module reach-through.
 */
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

  Optional<Budget> findByUserId(UUID userId);

  List<Budget> findAllByUserIdIn(Collection<UUID> userIds);
}
