package com.example.mealprep.feedback.domain.service;

import com.example.mealprep.feedback.api.dto.AnswerClarificationRequest;
import com.example.mealprep.feedback.api.dto.CorrectionRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackRequest;
import com.example.mealprep.feedback.api.dto.SubmitFeedbackResponse;
import java.util.UUID;

/**
 * Write surface for the feedback module. 01b implements {@link #submitFeedback}; the four other
 * methods are declared so subsequent tickets (01e/01f/01g) can plug in their impls without
 * modifying the interface mid-wave.
 *
 * <p>01b's {@code FeedbackServiceImpl} throws {@code UnsupportedOperationException} from the
 * unimplemented methods — controllers in 01b never call them.
 */
public interface FeedbackUpdateService {

  /**
   * 01b — persist a new feedback entry in {@code RECEIVED} state and publish {@code
   * FeedbackSubmittedEvent} after commit. Classification + routing run asynchronously (landing in
   * 01c/01d); 01b's submission stops at {@code RECEIVED}.
   */
  SubmitFeedbackResponse submitFeedback(UUID userId, SubmitFeedbackRequest request);

  /** 01f — user-driven correction of a misclassified routing. */
  SubmitFeedbackResponse correctMisclassification(
      UUID userId, UUID feedbackId, UUID routingId, CorrectionRequest request);

  /** 01e — user answers a pending clarification query, triggering re-classification. */
  SubmitFeedbackResponse answerClarificationQuery(
      UUID userId, UUID queryId, AnswerClarificationRequest request);

  /** 01g — scheduled sweep retrying entries stuck in {@code CLASSIFYING}. */
  void retryStuckClassifications();

  /** 01g — scheduled sweep marking expired clarification queries. */
  void expireOldClarificationQueries();
}
