package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.PlannerHintRequest;
import com.example.mealprep.adaptation.domain.enums.HintSeverity;
import com.example.mealprep.adaptation.domain.enums.HintType;
import com.example.mealprep.adaptation.domain.repository.PlannerHintRecordRepository;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.event.PlannerHintEmittedEvent;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Proves {@code PlannerHintEmittedEvent} fires exactly once {@code AFTER_COMMIT}. The capture
 * listener is {@code @Transactional(REQUIRES_NEW)} per decision 0010 §round-7 so its (no-op) JPA
 * context doesn't entangle the publisher's transaction.
 */
@SpringBootTest
@Import({TestContainersConfig.class, EventPublicationIT.EventCaptureConfig.class})
@ActiveProfiles("test")
class EventPublicationIT {

  @Autowired private AdaptationService adaptationService;
  @Autowired private PlannerHintRecordRepository repo;
  @Autowired private PlannerHintEmittedCapture capture;

  @AfterEach
  void cleanup() {
    repo.deleteAll();
    capture.events.clear();
  }

  @Test
  void emit_publishes_planner_hint_event_once_after_commit() {
    UUID recipeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    PlannerHintRequest req =
        new PlannerHintRequest(
            recipeId,
            versionId,
            UUID.randomUUID(),
            HintType.ABSORPTION_CONFLICT,
            "absorption conflict between slots",
            JsonNodeFactory.instance.objectNode(),
            HintSeverity.WARN,
            null,
            UUID.randomUUID());

    adaptationService.emitPlannerHint(req, UUID.randomUUID());

    assertThat(capture.events).hasSize(1);
    assertThat(capture.events.get(0).recipeId()).isEqualTo(recipeId);
    assertThat(capture.events.get(0).versionId()).isEqualTo(versionId);
  }

  @TestConfiguration
  static class EventCaptureConfig {
    @Bean
    PlannerHintEmittedCapture plannerHintEmittedCapture() {
      return new PlannerHintEmittedCapture();
    }
  }

  static class PlannerHintEmittedCapture {
    final List<PlannerHintEmittedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(PlannerHintEmittedEvent event) {
      events.add(event);
    }
  }
}
