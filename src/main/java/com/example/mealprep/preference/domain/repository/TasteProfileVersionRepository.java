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

  /**
   * Locate the snapshot a feedback-driven delta apply produced, by the {@code
   * feedback-<feedbackId>} origin trace the bridge stamps on both {@code feedbackRangeStart} and
   * {@code feedbackRangeEnd} (see {@code
   * feedback.bridge.PreferenceFeedbackBridgeImpl#buildRequest}). Used by the misclassification
   * reverter (feedback-01h) to find the version to roll back. Highest {@code documentVersion} first
   * so a retried apply (same trace, newer snapshot) resolves to the latest.
   */
  Optional<TasteProfileVersion>
      findFirstByTasteProfileIdAndFeedbackRangeStartOrderByDocumentVersionDesc(
          UUID tasteProfileId, String feedbackRangeStart);
}
