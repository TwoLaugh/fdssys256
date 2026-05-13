package com.example.mealprep.discovery.domain.repository;

import com.example.mealprep.discovery.domain.entity.DiscoverySource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link DiscoverySource}. Package-private — cross-module callers go
 * through {@code DiscoveryQueryService}.
 */
interface DiscoverySourceRepository extends JpaRepository<DiscoverySource, UUID> {

  Optional<DiscoverySource> findBySourceKey(String sourceKey);

  List<DiscoverySource> findByEnabledTrue();

  List<DiscoverySource> findBySourceKeyIn(Collection<String> sourceKeys);
}
