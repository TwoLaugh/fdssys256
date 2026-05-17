package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.domain.entity.ClarificationQuery;
import com.example.mealprep.feedback.domain.entity.ClarificationStatus;
import com.example.mealprep.feedback.domain.entity.FeedbackEntry;
import com.example.mealprep.feedback.domain.entity.SubmissionStatus;
import com.example.mealprep.feedback.domain.repository.ClarificationQueryRepository;
import com.example.mealprep.feedback.domain.repository.FeedbackEntryRepository;
import com.example.mealprep.feedback.domain.service.internal.ClarificationExpirer;
import com.example.mealprep.feedback.testdata.FeedbackTestData;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit tests for the per-query expiry helper: happy path, idempotent races. */
class ClarificationExpirerTest {

  private ClarificationQueryRepository clarificationRepo;
  private FeedbackEntryRepository entryRepo;
  private ClarificationExpirer expirer;

  @BeforeEach
  void setUp() {
    clarificationRepo = Mockito.mock(ClarificationQueryRepository.class);
    entryRepo = Mockito.mock(FeedbackEntryRepository.class);
    expirer = new ClarificationExpirer(clarificationRepo, entryRepo);
  }

  @Test
  void expireOne_pendingQuery_marksExpired_andFailsParentEntry() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "salt too much");
    parent.setSubmissionStatus(SubmissionStatus.CLARIFICATION_PENDING);
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    when(clarificationRepo.findById(q.getId())).thenReturn(Optional.of(q));
    when(entryRepo.updateSubmissionStatus(eq(parent.getId()), any(SubmissionStatus.class)))
        .thenReturn(1);

    expirer.expireOne(q.getId());

    assertThat(q.getStatus()).isEqualTo(ClarificationStatus.EXPIRED);
    verify(clarificationRepo).save(q);
    verify(entryRepo).updateSubmissionStatus(parent.getId(), SubmissionStatus.FAILED);
  }

  @Test
  void expireOne_alreadyExpired_isNoOp() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    q.setStatus(ClarificationStatus.EXPIRED);
    when(clarificationRepo.findById(q.getId())).thenReturn(Optional.of(q));

    expirer.expireOne(q.getId());

    verify(clarificationRepo, never()).save(any());
    verify(entryRepo, never()).updateSubmissionStatus(any(), any());
  }

  @Test
  void expireOne_racedToAnswered_isNoOp() {
    FeedbackEntry parent = FeedbackTestData.feedbackEntry(UUID.randomUUID(), "x");
    ClarificationQuery q = FeedbackTestData.clarificationQuery(parent);
    q.setStatus(ClarificationStatus.ANSWERED);
    when(clarificationRepo.findById(q.getId())).thenReturn(Optional.of(q));

    expirer.expireOne(q.getId());

    verify(clarificationRepo, never()).save(any());
    verify(entryRepo, never()).updateSubmissionStatus(any(), any());
  }

  @Test
  void expireOne_missingQuery_isNoOp() {
    UUID id = UUID.randomUUID();
    when(clarificationRepo.findById(id)).thenReturn(Optional.empty());

    expirer.expireOne(id);

    verify(clarificationRepo, never()).save(any());
    verify(entryRepo, never()).updateSubmissionStatus(any(), any());
  }
}
