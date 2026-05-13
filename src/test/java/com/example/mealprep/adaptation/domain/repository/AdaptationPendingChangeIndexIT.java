package com.example.mealprep.adaptation.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.adaptation.domain.entity.AdaptationJob;
import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ApprovalPolicy;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.JobPriority;
import com.example.mealprep.adaptation.domain.enums.JobSource;
import com.example.mealprep.adaptation.domain.enums.JobStatus;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.recipe.domain.entity.Catalogue;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Behaviour of the partial-unique index {@code (recipe_id, change_dimension) WHERE status =
 * 'PENDING'} on {@code adaptation_pending_changes}: enforces atomic supersession but only against
 * the active row, leaving terminal-status rows (SUPERSEDED / REJECTED / etc.) unaffected.
 *
 * <p>Also exercises the FK cascade from {@code adaptation_jobs.id ->
 * adaptation_pending_changes.job_id}.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class AdaptationPendingChangeIndexIT {

  @Autowired private AdaptationJobRepository jobRepository;
  @Autowired private PendingChangeRepository pendingChangeRepository;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM adaptation_pending_changes");
    jdbcTemplate.update("DELETE FROM adaptation_jobs");
  }

  @Test
  void two_pending_rows_for_same_recipe_dimension_violate_partial_unique_index() throws Exception {
    UUID recipeId = UUID.randomUUID();
    UUID job1 = saveJob();
    UUID job2 = saveJob();

    pendingChangeRepository.saveAndFlush(
        newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job1));

    assertThatThrownBy(
            () ->
                pendingChangeRepository.saveAndFlush(
                    newPending(
                        recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job2)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void second_pending_allowed_once_first_is_superseded() throws Exception {
    UUID recipeId = UUID.randomUUID();
    UUID job1 = saveJob();
    UUID job2 = saveJob();

    PendingChange first =
        pendingChangeRepository.saveAndFlush(
            newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job1));

    // Flip the existing PENDING to SUPERSEDED first; the FK on superseded_by is satisfied later
    // when the new row inserts and we update the pointer. Mirrors LLD §V20260615120100: the partial
    // unique index allows the new PENDING only once the old one has left PENDING status.
    // Re-bind `first` to the returned managed entity so @Version increments don't go stale on the
    // second update (round-8 retro: StaleObjectStateException when reusing the pre-save reference).
    first.setStatus(PendingChangeStatus.SUPERSEDED);
    first = pendingChangeRepository.saveAndFlush(first);

    PendingChange second =
        pendingChangeRepository.saveAndFlush(
            newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job2));

    first.setSupersededBy(second.getId());
    first = pendingChangeRepository.saveAndFlush(first);

    assertThat(second.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
    assertThat(pendingChangeRepository.findAll()).hasSize(2);
    assertThat(pendingChangeRepository.findById(first.getId()).orElseThrow().getSupersededBy())
        .isEqualTo(second.getId());
  }

  @Test
  void rejected_does_not_block_a_new_pending() throws Exception {
    UUID recipeId = UUID.randomUUID();
    UUID job1 = saveJob();
    UUID job2 = saveJob();

    PendingChange first =
        pendingChangeRepository.saveAndFlush(
            newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job1));
    first.setStatus(PendingChangeStatus.REJECTED);
    first.setResolvedAt(Instant.now());
    pendingChangeRepository.saveAndFlush(first);

    PendingChange second =
        pendingChangeRepository.saveAndFlush(
            newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job2));

    assertThat(second.getStatus()).isEqualTo(PendingChangeStatus.PENDING);
  }

  @Test
  void deleting_job_cascades_to_pending_change_rows() throws Exception {
    UUID job = saveJob();
    UUID recipeId = UUID.randomUUID();
    pendingChangeRepository.saveAndFlush(
        newPending(recipeId, ChangeDimension.SALT_LEVEL, PendingChangeStatus.PENDING, job));
    assertThat(pendingChangeRepository.findAll()).hasSize(1);

    jdbcTemplate.update("DELETE FROM adaptation_jobs WHERE id = ?", job);

    assertThat(pendingChangeRepository.findAll()).isEmpty();
  }

  // ---------------- helpers ----------------

  private UUID saveJob() throws Exception {
    UUID id = UUID.randomUUID();
    AdaptationJob job =
        AdaptationJob.builder()
            .id(id)
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .catalogue(Catalogue.USER)
            .source(JobSource.FEEDBACK)
            .priority(JobPriority.SYNC)
            .approvalPolicy(ApprovalPolicy.PENDING_CHANGE)
            .status(JobStatus.RUNNING)
            .inputs(objectMapper.readTree("{}"))
            .traceId(UUID.randomUUID())
            .enqueuedAt(Instant.now())
            .build();
    jobRepository.saveAndFlush(job);
    return id;
  }

  private PendingChange newPending(
      UUID recipeId, ChangeDimension dimension, PendingChangeStatus status, UUID jobId)
      throws Exception {
    return PendingChange.builder()
        .id(UUID.randomUUID())
        .recipeId(recipeId)
        .userId(UUID.randomUUID())
        .jobId(jobId)
        .traceId(UUID.randomUUID())
        .changeDimension(dimension)
        .proposedDiff(objectMapper.readTree("{\"ops\":[]}"))
        .proposedClassification(AdaptationClassification.VERSION)
        .baseVersionId(UUID.randomUUID())
        .baseBranchId(UUID.randomUUID())
        .reasoning("reasoning")
        .confidence(new BigDecimal("0.700"))
        .impactScore(new BigDecimal("0.500"))
        .promptTemplateVersion("v1.0.0")
        .status(status)
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(14, ChronoUnit.DAYS))
        .build();
  }
}
