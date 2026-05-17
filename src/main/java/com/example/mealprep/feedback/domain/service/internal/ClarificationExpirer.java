package com.example.mealprep.feedback.domain.service.internal;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sibling component for the per-query expiry transaction. Extracted out of {@code
 * FeedbackServiceImpl} so the {@code REQUIRES_NEW} {@link Transactional} boundary is honoured by
 * Spring's proxy — calling a {@code @Transactional} method from another method of the <em>same</em>
 * bean bypasses the proxy and silently drops the annotation (wave-3 self-invocation gotcha). Each
 * query expires in its own transaction so one failure does not drop the rest of the sweep.
 */
@Component
public class ClarificationExpirer {

  private final ClarificationQueryRepository clarificationQueryRepository;
  private final FeedbackEntryRepository feedbackEntryRepository;

  public ClarificationExpirer(
      ClarificationQueryRepository clarificationQueryRepository,
      FeedbackEntryRepository feedbackEntryRepository) {
    this.clarificationQueryRepository = clarificationQueryRepository;
    this.feedbackEntryRepository = feedbackEntryRepository;
  }

  /**
   * Expire a single clarification query in its own transaction. Re-reads the row under the new
   * transaction and no-ops if it raced to a non-{@code PENDING} state (idempotent — concurrent
   * answer or a prior sweep). The parent feedback entry's status flips to {@code FAILED} via the
   * native UPDATE (round-8 retro: avoids the {@code @Version} race on {@code FeedbackEntry}).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireOne(UUID queryId) {
    ClarificationQuery q = clarificationQueryRepository.findById(queryId).orElse(null);
    if (q == null || q.getStatus() != ClarificationStatus.PENDING) {
      return; // raced to ANSWERED / already EXPIRED — no-op, idempotent.
    }
    q.setStatus(ClarificationStatus.EXPIRED);
    clarificationQueryRepository.save(q);

    FeedbackEntry entry = q.getFeedbackEntry();
    int rows =
        feedbackEntryRepository.updateSubmissionStatus(entry.getId(), SubmissionStatus.FAILED);
    if (rows == 0) {
      // Parent entry vanished concurrently — the clarification row is already EXPIRED above,
      // which is the correct terminal state for an orphaned query. Let the tx commit.
      return;
    }
  }
}
