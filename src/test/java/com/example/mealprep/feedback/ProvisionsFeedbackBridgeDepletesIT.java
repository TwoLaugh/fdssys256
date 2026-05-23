package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.mealprep.auth.api.dto.RegisterRequest;
import com.example.mealprep.auth.config.AuthProperties;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.testdata.AuthTestData;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemRanOutEvent;
import com.example.mealprep.provisions.testdata.ProvisionsTestData;
import com.example.mealprep.testsupport.TestContainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Testcontainers IT for the {@code MARK_DEPLETED} provisions-feedback depletion path
 * (provisions/01i §AFTER_COMMIT atomicity). Drives the real {@link ProvisionsFeedbackBridge} Spring
 * bean — the same call the dispatcher makes — against a real Postgres with seeded ACTIVE inventory
 * rows, and asserts the full atomic outcome:
 *
 * <ul>
 *   <li>every matching ACTIVE row is flipped to {@code EXHAUSTED} (zero remaining),
 *   <li>one {@link ItemRanOutEvent} is published per exhausted row (AFTER_COMMIT),
 *   <li>the bridge's {@code DISPATCHED} idempotency row commits together with the inventory flips.
 * </ul>
 *
 * <p>The bridge runs under its {@code REQUIRES_NEW} {@code TransactionTemplate}; {@code
 * markExhausted} (plain {@code @Transactional}, REQUIRED) joins that template tx, so the
 * inventory-status flips + audit rows + events + idempotency row commit as one unit. Observing the
 * AFTER_COMMIT {@link ItemRanOutEvent} after the call returns proves the surrounding tx committed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, ProvisionsFeedbackBridgeDepletesIT.DepleteCaptureConfig.class})
@ActiveProfiles("test")
class ProvisionsFeedbackBridgeDepletesIT {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private AuthProperties authProperties;
  @Autowired private InventoryItemRepository inventoryItemRepository;
  @Autowired private ProvisionsFeedbackBridge provisionsBridge;
  @Autowired private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RanOutCapture ranOutCapture;

  @AfterEach
  void cleanup() {
    idempotencyRepository.deleteAll();
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    sessionRepository.deleteAll();
    userRepository.deleteAll();
    ranOutCapture.clear();
  }

  @Test
  void markDepleted_multipleActiveRows_flipsAllExhausted_publishesEvents_booksDispatched()
      throws Exception {
    UUID userId = registerUser();
    UUID rowOlder = seedActiveSoySauce(userId, "2026-06-01");
    UUID rowNewer = seedActiveSoySauce(userId, "2026-07-01");

    UUID feedbackId = UUID.randomUUID();
    ProvisionsFeedbackBridge.Result result =
        provisionsBridge.applyFeedback(markDepleted(feedbackId, userId, "soy_sauce"));

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");

    // Both rows flipped EXHAUSTED.
    assertThat(inventoryItemRepository.findById(rowOlder).orElseThrow().getItemStatus())
        .isEqualTo(ItemLifecycleStatus.EXHAUSTED);
    assertThat(inventoryItemRepository.findById(rowNewer).orElseThrow().getItemStatus())
        .isEqualTo(ItemLifecycleStatus.EXHAUSTED);

    // One ItemRanOutEvent per exhausted row, observed AFTER_COMMIT (proves the tx committed).
    assertThat(ranOutCapture.events()).hasSize(2);
    assertThat(ranOutCapture.events())
        .allSatisfy(e -> assertThat(e.userId()).isEqualTo(userId))
        .flatExtracting(ItemRanOutEvent::affectedItemIds)
        .containsExactlyInAnyOrder(rowOlder, rowNewer);

    // Bridge idempotency row committed DISPATCHED in the same unit of work.
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PROVISIONS)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  void markDepleted_noActiveRows_isIdempotentNoOp_booksDispatched_noEvent() throws Exception {
    UUID userId = registerUser();

    UUID feedbackId = UUID.randomUUID();
    ProvisionsFeedbackBridge.Result result =
        provisionsBridge.applyFeedback(markDepleted(feedbackId, userId, "soy_sauce"));

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(result.payload()).containsEntry("noop", "nothing-to-deplete");
    assertThat(ranOutCapture.events()).isEmpty();
    assertThat(
            idempotencyRepository
                .findByFeedbackIdAndDestination(feedbackId, Destination.PROVISIONS)
                .orElseThrow()
                .getStatus())
        .isEqualTo(BridgeDispatchStatus.DISPATCHED);
  }

  @Test
  void markDepleted_crossTenant_doesNotExhaustAnotherUsersStock() throws Exception {
    UUID userA = registerUser();
    UUID userB = registerUser();
    UUID rowB = seedActiveSoySauce(userB, "2026-06-01");

    UUID feedbackId = UUID.randomUUID();
    ProvisionsFeedbackBridge.Result result =
        provisionsBridge.applyFeedback(markDepleted(feedbackId, userA, "soy_sauce"));

    // A's depletion finds no rows → no-op; B's stock stays ACTIVE.
    assertThat(result.payload()).containsEntry("noop", "nothing-to-deplete");
    assertThat(inventoryItemRepository.findById(rowB).orElseThrow().getItemStatus())
        .isEqualTo(ItemLifecycleStatus.ACTIVE);
    assertThat(ranOutCapture.events()).isEmpty();
  }

  // ---------------- helpers ----------------

  private UUID registerUser() throws Exception {
    String username = "alice-" + AuthTestData.shortId();
    RegisterRequest body = AuthTestData.registerRequest(username);
    MvcResult result =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        objectMapper.readTree(result.getResponse().getContentAsString()).get("userId").asText());
  }

  private UUID seedActiveSoySauce(UUID userId, String expiry) {
    InventoryItem item =
        ProvisionsTestData.quantityTrackedItem(userId)
            .name("Soy Sauce")
            .ingredientMappingKey("soy_sauce")
            .quantity(new BigDecimal("150.000"))
            .unit("ml")
            .expiryDate(LocalDate.parse(expiry))
            .itemStatus(ItemLifecycleStatus.ACTIVE)
            .build();
    return inventoryItemRepository.saveAndFlush(item).getId();
  }

  private static ProvisionsFeedbackBridge.Input markDepleted(
      UUID feedbackId, UUID userId, String ingredientKey) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("provisionsAction", "MARK_DEPLETED");
    payload.put("ingredientMappingKey", ingredientKey);
    return new ProvisionsFeedbackBridge.Input(
        feedbackId,
        userId,
        new BigDecimal("0.9"),
        "I'm out of " + ingredientKey,
        UUID.randomUUID(),
        payload);
  }

  // ---------------- AFTER_COMMIT capture wiring ----------------

  @TestConfiguration
  static class DepleteCaptureConfig {
    @Bean
    RanOutCapture ranOutCapture() {
      return new RanOutCapture();
    }
  }

  static class RanOutCapture {
    private final List<ItemRanOutEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onRanOut(ItemRanOutEvent event) {
      events.add(event);
    }

    List<ItemRanOutEvent> events() {
      return events;
    }

    void clear() {
      events.clear();
    }
  }
}
