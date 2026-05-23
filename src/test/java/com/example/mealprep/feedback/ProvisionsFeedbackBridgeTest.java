package com.example.mealprep.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.feedback.bridge.ProvisionsFeedbackBridgeImpl;
import com.example.mealprep.feedback.domain.entity.BridgeDispatchStatus;
import com.example.mealprep.feedback.domain.repository.FeedbackBridgeIdempotencyRepository;
import com.example.mealprep.feedback.exception.FeedbackBridgeDispatchFailedException;
import com.example.mealprep.feedback.spi.Destination;
import com.example.mealprep.feedback.spi.ProvisionsFeedbackBridge;
import com.example.mealprep.feedback.testdata.InlineTransactionTemplate;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.exception.EquipmentNotFoundException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the real PROVISIONS bridge: equipment removal (real end-to-end + idempotent
 * no-op), inventory depletion (single-row, multi-row, no-row no-op, missing-key FAILED),
 * unsupported action, confidence floor.
 */
class ProvisionsFeedbackBridgeTest {

  private static final Instant NOW = Instant.parse("2026-05-22T10:00:00Z");

  private ProvisionUpdateService provisionUpdateService;
  private ProvisionQueryService provisionQueryService;
  private FeedbackBridgeIdempotencyRepository idempotencyRepository;
  private ProvisionsFeedbackBridgeImpl bridge;

  @BeforeEach
  void setUp() {
    provisionUpdateService = Mockito.mock(ProvisionUpdateService.class);
    provisionQueryService = Mockito.mock(ProvisionQueryService.class);
    idempotencyRepository = Mockito.mock(FeedbackBridgeIdempotencyRepository.class);
    when(idempotencyRepository.findByFeedbackIdAndDestination(any(), any()))
        .thenReturn(Optional.empty());
    when(idempotencyRepository.insertIfAbsent(any(), any(), anyString(), anyString(), any()))
        .thenReturn(1);
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    bridge =
        new ProvisionsFeedbackBridgeImpl(
            provisionUpdateService,
            provisionQueryService,
            idempotencyRepository,
            new InlineTransactionTemplate(),
            clock);
  }

  @Test
  void removeEquipment_callsDeleteEquipment_booksDispatched() {
    ProvisionsFeedbackBridge.Input input = removeEquipment("food processor", new BigDecimal("0.9"));

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    verify(provisionUpdateService).deleteEquipment(eq(input.userId()), eq("food processor"));
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void removeEquipment_whenAbsent_isIdempotentNoOp_booksDispatched() {
    ProvisionsFeedbackBridge.Input input = removeEquipment("spiralizer", new BigDecimal("0.9"));
    doThrow(new EquipmentNotFoundException(input.userId(), "spiralizer"))
        .when(provisionUpdateService)
        .deleteEquipment(any(), eq("spiralizer"));

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(result.payload()).containsEntry("noop", "equipment-absent");
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void markDepleted_singleActiveRow_exhaustsItAndBooksDispatched() {
    ProvisionsFeedbackBridge.Input input =
        action("MARK_DEPLETED", "soy_sauce", new BigDecimal("0.9"));
    InventoryItemDto row = activeRow(input.userId(), "soy_sauce");
    when(provisionQueryService.getActiveInventoryByMappingKey(input.userId(), "soy_sauce"))
        .thenReturn(List.of(row));

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    verify(provisionUpdateService).markExhausted(eq(row.id()), eq(input.userId()));
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void markDepleted_multipleActiveRows_exhaustsEachAndBooksDispatched() {
    ProvisionsFeedbackBridge.Input input =
        action("MARK_DEPLETED", "soy_sauce", new BigDecimal("0.9"));
    InventoryItemDto rowA = activeRow(input.userId(), "soy_sauce");
    InventoryItemDto rowB = activeRow(input.userId(), "soy_sauce");
    when(provisionQueryService.getActiveInventoryByMappingKey(input.userId(), "soy_sauce"))
        .thenReturn(List.of(rowA, rowB));

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    verify(provisionUpdateService).markExhausted(eq(rowA.id()), eq(input.userId()));
    verify(provisionUpdateService).markExhausted(eq(rowB.id()), eq(input.userId()));
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void markDepleted_noActiveRows_isIdempotentNoOp_booksDispatched_andDoesNotExhaust() {
    ProvisionsFeedbackBridge.Input input =
        action("MARK_DEPLETED", "soy_sauce", new BigDecimal("0.9"));
    when(provisionQueryService.getActiveInventoryByMappingKey(input.userId(), "soy_sauce"))
        .thenReturn(List.of());

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "DISPATCHED");
    assertThat(result.payload()).containsEntry("noop", "nothing-to-deplete");
    verify(provisionUpdateService, never()).markExhausted(any(), any());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.DISPATCHED.name()),
            eq(NOW));
  }

  @Test
  void markDepleted_missingIngredientKey_booksFailed_andThrows_andDoesNotLookUp() {
    ProvisionsFeedbackBridge.Input input = action("MARK_DEPLETED", null, new BigDecimal("0.9"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    verify(provisionQueryService, never()).getActiveInventoryByMappingKey(any(), any());
    verify(provisionUpdateService, never()).markExhausted(any(), any());
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void unsupportedAction_booksFailed_andThrows() {
    ProvisionsFeedbackBridge.Input input = action("ADJUST_BUDGET", null, new BigDecimal("0.9"));

    assertThatThrownBy(() -> bridge.applyFeedback(input))
        .isInstanceOf(FeedbackBridgeDispatchFailedException.class);

    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq("PROVISIONS"),
            eq(BridgeDispatchStatus.FAILED.name()),
            eq(NOW));
  }

  @Test
  void belowConfidenceFloor_booksRejected() {
    ProvisionsFeedbackBridge.Input input = removeEquipment("food processor", new BigDecimal("0.3"));

    ProvisionsFeedbackBridge.Result result = bridge.applyFeedback(input);

    assertThat(result.payload()).containsEntry("status", "REJECTED_LOW_CONFIDENCE");
    verify(idempotencyRepository)
        .insertIfAbsent(
            any(),
            eq(input.feedbackId()),
            eq(Destination.PROVISIONS.name()),
            eq(BridgeDispatchStatus.REJECTED_LOW_CONFIDENCE.name()),
            eq(NOW));
  }

  private ProvisionsFeedbackBridge.Input removeEquipment(String name, BigDecimal confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("provisionsAction", "REMOVE_EQUIPMENT");
    payload.put("equipmentName", name);
    return new ProvisionsFeedbackBridge.Input(
        UUID.randomUUID(),
        UUID.randomUUID(),
        confidence,
        "don't have a " + name,
        UUID.randomUUID(),
        payload);
  }

  private ProvisionsFeedbackBridge.Input action(String action, String key, BigDecimal confidence) {
    ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.put("provisionsAction", action);
    if (key != null) {
      payload.put("ingredientMappingKey", key);
    }
    return new ProvisionsFeedbackBridge.Input(
        UUID.randomUUID(), UUID.randomUUID(), confidence, "feedback", UUID.randomUUID(), payload);
  }

  private static InventoryItemDto activeRow(UUID userId, String mappingKey) {
    return new InventoryItemDto(
        UUID.randomUUID(),
        userId,
        mappingKey,
        "condiment",
        StorageLocation.CUPBOARD,
        TrackingMode.STATUS,
        null,
        null,
        null,
        null,
        false,
        LocalDate.parse("2026-12-01"),
        mappingKey,
        null,
        ItemSource.MANUAL_ADD,
        null,
        ItemLifecycleStatus.ACTIVE,
        null,
        NOW,
        NOW,
        0L);
  }
}
