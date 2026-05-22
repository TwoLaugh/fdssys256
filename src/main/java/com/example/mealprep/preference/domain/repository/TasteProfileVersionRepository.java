package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link TasteProfileVersion}. Append-only — only insertion and
 * paginated read by taste-profile id are exposed.
 */
public interface TasteProfileVersionRepository extends JpaRepository<TasteProfileVersion, UUID> {

  Page<TasteProfileVersion> findByTasteProfileIdOrderByDocumentVersionDesc(
      UUID tasteProfileId, Pageable pageable);

  Optional<TasteProfileVersion> findByTasteProfileIdAndDocumentVersion(
      UUID tasteProfileId, int documentVersion);
}
