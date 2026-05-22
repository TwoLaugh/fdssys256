package com.example.mealprep.preference.domain.repository;

import com.example.mealprep.preference.domain.entity.PreferenceArchiveEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link PreferenceArchiveEntry}. Package-private to the preference
 * module (per the {@code PreferenceBoundaryTest} ArchUnit rule) — cross-module callers go through
 * {@code PreferenceArchiveQueryService} / {@code PreferenceArchiveUpdateService}.
 */
public interface PreferenceArchiveRepository extends JpaRepository<PreferenceArchiveEntry, UUID> {

  /** Currently-archived (not yet re-promoted) entry for a logical preference. */
  Optional<PreferenceArchiveEntry> findByUserIdAndFieldPathAndItemKeyAndRePromotedAtIsNull(
      UUID userId, String fieldPath, String itemKey);

  /** User-facing archive listing (newest archived first). */
  Page<PreferenceArchiveEntry> findByUserIdOrderByArchivedAtDesc(UUID userId, Pageable p);

  /** All archive entries for a user — used by the delta-update task to detect re-emergence. */
  List<PreferenceArchiveEntry> findAllByUserId(UUID userId);

  /** Filter by field path — backs the user-facing "show me ingredient archive" view. */
  Page<PreferenceArchiveEntry> findByUserIdAndFieldPathStartingWithOrderByArchivedAtDesc(
      UUID userId, String fieldPathPrefix, Pageable p);

  /** Active (not-yet-re-promoted) entries — used by analytics. */
  long countByUserIdAndRePromotedAtIsNull(UUID userId);
}
