package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link TasteProfile}. Package-private to the preference module —
 * cross-module callers go through {@code TasteProfileQueryService} / {@code
 * TasteProfileUpdateService}.
 */
public interface TasteProfileRepository extends JpaRepository<TasteProfile, UUID> {

  Optional<TasteProfile> findByUserId(UUID userId);

  List<TasteProfile> findByUserIdIn(Collection<UUID> userIds);

  /**
   * Used by the future async embedding listener (vector ticket); ships now so the listener compiles
   * later.
   */
  List<TasteProfile> findByTasteVectorStatus(TasteVectorStatus status);
}
