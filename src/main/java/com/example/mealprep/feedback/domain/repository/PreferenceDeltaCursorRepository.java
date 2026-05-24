package com.example.mealprep.feedback.domain.repository;

import com.example.mealprep.feedback.domain.entity.PreferenceDeltaCursor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link PreferenceDeltaCursor} (preference-01g). Package-visible within
 * the feedback module only — cross-module callers never see it (ArchUnit {@code
 * noCrossModuleRepoImport}).
 */
public interface PreferenceDeltaCursorRepository
    extends JpaRepository<PreferenceDeltaCursor, UUID> {

  /**
   * Single-user cursor lookup. Returns empty before the user's first PREFERENCE-routed feedback.
   */
  Optional<PreferenceDeltaCursor> findByUserId(UUID userId);

  /**
   * WEEKLY-sweep query: every user with at least one pending PREFERENCE-routed feedback since their
   * last run. Backed by the partial index {@code idx_feedback_pref_delta_cursor_pending}.
   */
  @Query("SELECT c FROM PreferenceDeltaCursor c WHERE c.pendingCount >= :minPending")
  List<PreferenceDeltaCursor> findWithPendingAtLeast(@Param("minPending") int minPending);
}
