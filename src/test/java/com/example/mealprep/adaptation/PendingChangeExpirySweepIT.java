package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.domain.entity.PendingChange;
import com.example.mealprep.adaptation.domain.enums.AdaptationClassification;
import com.example.mealprep.adaptation.domain.enums.ChangeDimension;
import com.example.mealprep.adaptation.domain.enums.PendingChangeStatus;
import com.example.mealprep.adaptation.domain.repository.PendingChangeRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * DB-backed: seeds PENDING rows directly via the repository (no POST → no async runner race per
 * wave-3 retro 0012) and asserts the sweep flips only the expired ones.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
class PendingChangeExpirySweepIT {

  @Autowired private AdaptationService adaptationService;
  @Autowired private PendingChangeRepository repo;

  @AfterEach
  void cleanup() {
    repo.deleteAll();
  }

  private UUID seed(Instant expiresAt) {
    UUID id = UUID.randomUUID();
    repo.saveAndFlush(
        PendingChange.builder()
            .id(id)
            .recipeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .jobId(UUID.randomUUID())
            .traceId(UUID.randomUUID())
            .changeDimension(ChangeDimension.SALT_LEVEL)
            .proposedDiff(JsonNodeFactory.instance.objectNode())
            .proposedClassification(AdaptationClassification.VERSION)
            .baseVersionId(UUID.randomUUID())
            .baseBranchId(UUID.randomUUID())
            .reasoning("r")
            .confidence(new BigDecimal("0.750"))
            .impactScore(new BigDecimal("0.500"))
            .promptTemplateVersion("v1")
            .status(PendingChangeStatus.PENDING)
            .createdAt(Instant.now().minus(20, ChronoUnit.DAYS))
            .expiresAt(expiresAt)
            .build());
    return id;
  }

  @Test
  void sweep_flips_expired_pending_and_leaves_in_window_untouched() {
    UUID expired = seed(Instant.now().minus(1, ChronoUnit.DAYS));
    UUID fresh = seed(Instant.now().plus(5, ChronoUnit.DAYS));

    int touched = adaptationService.sweepExpiredPendingChanges();

    assertThat(touched).isEqualTo(1);
    assertThat(repo.findById(expired).orElseThrow().getStatus())
        .isEqualTo(PendingChangeStatus.EXPIRED);
    assertThat(repo.findById(expired).orElseThrow().getResolvedAt()).isNotNull();
    assertThat(repo.findById(fresh).orElseThrow().getStatus())
        .isEqualTo(PendingChangeStatus.PENDING);
  }

  @Test
  void sweep_with_no_expired_rows_returns_zero() {
    seed(Instant.now().plus(5, ChronoUnit.DAYS));
    assertThat(adaptationService.sweepExpiredPendingChanges()).isZero();
  }
}
