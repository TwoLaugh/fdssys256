package com.example.mealprep.provisions.domain.repository;

import com.example.mealprep.provisions.domain.entity.Equipment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Equipment}. Cross-module callers go through {@code
 * ProvisionQueryService} / {@code ProvisionUpdateService} — enforced by {@code
 * ProvisionsBoundaryTest} (ArchUnit). The interface is {@code public} only because the in-module
 * {@code domain.service.internal} package needs to inject it; package-private would prevent any
 * reference from another package, including same-module ones. The boundary test, not Java
 * visibility, fences cross-module reach-through.
 */
public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {

  List<Equipment> findAllByUserIdOrderByNameAsc(UUID userId);

  List<Equipment> findAllByUserIdAndAvailableTrueOrderByNameAsc(UUID userId);

  Optional<Equipment> findByUserIdAndName(UUID userId, String name);
}
