package com.example.mealprep.feedback.domain.service;

import com.example.mealprep.feedback.api.dto.ClarificationQueryDto;
import com.example.mealprep.feedback.api.dto.FeedbackEntryDto;
import com.example.mealprep.feedback.api.dto.MisclassificationCorrectionDto;
import com.example.mealprep.feedback.api.dto.RoutingDecisionDto;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read surface for the feedback module. All methods are per-user — the {@code userId} parameter is
 * the caller's id (resolved by {@code CurrentUserResolver}); every repository query is filtered to
 * the caller's rows to prevent cross-user reads.
 *
 * <p>01b implements the entry-, batch-, and routing-decision reads; clarification + correction
 * reads are declared here so 01e/01f land as drop-in impls (no interface changes mid-wave).
 */
public interface FeedbackQueryService {

  /** 01b — fetch one feedback entry, eager-loading its routing log, for the caller. */
  Optional<FeedbackEntryDto> getById(UUID userId, UUID feedbackId);

  /**
   * 01b — batch sibling of {@link #getById}. Each row is filtered by {@code userId}; missing or
   * other-user ids are silently omitted (caller may compare result size to input size to detect).
   */
  List<FeedbackEntryDto> getByIds(UUID userId, List<UUID> feedbackIds);

  /** 01b — paginated newest-first list of the caller's feedback entries. */
  Page<FeedbackEntryDto> listByUser(UUID userId, Pageable pageable);

  /** 01b — fetch one routing-log row, scoped to the caller's entries. */
  Optional<RoutingDecisionDto> getRoutingDecision(UUID userId, UUID routingId);

  /** 01e — paginated clarification queries for the caller, filtered by status. */
  Page<ClarificationQueryDto> listClarificationQueries(
      UUID userId, ClarificationStatus status, Pageable pageable);

  /** 01e — fetch one clarification query for the caller. */
  Optional<ClarificationQueryDto> getClarificationQuery(UUID userId, UUID queryId);

  /** 01f — paginated misclassification corrections for the caller, newest-first. */
  Page<MisclassificationCorrectionDto> listCorrections(UUID userId, Pageable pageable);
}
