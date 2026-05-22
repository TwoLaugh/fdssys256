package com.example.mealprep.preference.domain.service;

import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import java.util.UUID;

/**
 * Write surface over the preference archive. Public interface (re-exported via {@code
 * PreferenceModule}), but <b>only the future {@code TasteProfileDeltaApplier} should call it</b> —
 * the archive is never written from a REST endpoint. Both methods run inside the applier's
 * single-flight-per-user transactional boundary.
 */
public interface PreferenceArchiveUpdateService {

  /**
   * Called by {@code TasteProfileDeltaApplier} when applying an {@code Archive} delta op. Inserts a
   * new archive row (no upsert — re-archiving the same logical item appends another row). Validates
   * {@code request} programmatically; publishes a {@code PreferenceArchivedEvent} {@code
   * AFTER_COMMIT}.
   */
  PreferenceArchiveEntryDto archiveItem(UUID userId, ArchiveItemRequest request);

  /**
   * Called by {@code TasteProfileDeltaApplier} when applying a {@code RePromote} delta op. Marks
   * the currently-archived (not yet re-promoted) entry for {@code (userId, fieldPath, itemKey)} as
   * re-promoted; the row remains as history. Publishes a {@code PreferenceRePromotedEvent} {@code
   * AFTER_COMMIT}.
   *
   * @throws com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException if no
   *     matching unpromoted entry exists.
   */
  PreferenceArchiveEntryDto markRePromoted(UUID userId, String fieldPath, String itemKey);
}
