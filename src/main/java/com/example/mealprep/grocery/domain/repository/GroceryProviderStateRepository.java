package com.example.mealprep.grocery.domain.repository;

import com.example.mealprep.grocery.domain.entity.GroceryProviderState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GroceryProviderState}. Package-private. Standard-shape per
 * lld/grocery.md line 541.
 */
interface GroceryProviderStateRepository extends JpaRepository<GroceryProviderState, UUID> {

  Optional<GroceryProviderState> findByUserIdAndProviderKey(UUID userId, String providerKey);

  List<GroceryProviderState> findAllByScheduledRefreshEnabledTrue();
}
