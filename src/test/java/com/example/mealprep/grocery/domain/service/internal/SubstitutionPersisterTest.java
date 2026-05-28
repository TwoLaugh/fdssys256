package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GroceryOrderLine;
import com.example.mealprep.grocery.domain.entity.GroceryOrderStatus;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.OrderLineStatus;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.internal.providers.SubstitutionProposal;
import com.example.mealprep.grocery.event.SubstitutionProposedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit test for the {@link SubstitutionPersister} (grocery-01f). Verifies: a parseable provider
 * proposal lands {@code PENDING_USER_REVIEW} with the matched line's mapping key; an opaque payload
 * (no substitute product id) lands {@code UNPARSED} with the raw payload preserved; exactly one
 * {@link SubstitutionProposedEvent} fires per persisted proposal; a null/empty input persists
 * nothing (no auto-accept ever — no path sets ACCEPTED/REJECTED here). Pure unit test — all
 * collaborators mocked.
 */
@ExtendWith(MockitoExtension.class)
class SubstitutionPersisterTest {

  private static final Instant NOW = Instant.parse("2026-05-28T10:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private GroceryOrderDataGateway dataGateway;
  @Mock private ApplicationEventPublisher eventPublisher;

  private SubstitutionPersister persister() {
    return new SubstitutionPersister(dataGateway, eventPublisher, clock);
  }

  private GroceryOrder orderWithLine(String key) {
    UUID orderId = UUID.randomUUID();
    GroceryOrder order =
        GroceryOrder.builder()
            .id(orderId)
            .userId(UUID.randomUUID())
            .householdId(UUID.randomUUID())
            .shoppingListId(UUID.randomUUID())
            .providerKey("fake")
            .status(GroceryOrderStatus.DELIVERED)
            .currency("GBP")
            .traceId(orderId)
            .automationFailureLog(new ArrayList<>())
            .lines(new ArrayList<>())
            .build();
    GroceryOrderLine line =
        GroceryOrderLine.builder()
            .id(UUID.randomUUID())
            .groceryOrder(order)
            .ingredientMappingKey(key)
            .displayName("Original " + key)
            .providerProductId("fake-sku-" + key)
            .quantityRequested(new BigDecimal("1.000"))
            .quantityUnit("kg")
            .lineStatus(OrderLineStatus.DELIVERED)
            .build();
    order.getLines().add(line);
    return order;
  }

  private void stubSaveEchoes() {
    when(dataGateway.saveProposal(any(GrocerySubstitutionProposal.class)))
        .thenAnswer(inv -> inv.getArgument(0, GrocerySubstitutionProposal.class));
  }

  @Test
  void persistAll_parseable_landsPendingUserReview_withMatchedLineKey() {
    stubSaveEchoes();
    GroceryOrder order = orderWithLine("white rice");
    SubstitutionProposal proposal =
        new SubstitutionProposal(
            "fake-sku-white rice",
            "White rice",
            "fake-sku-brown rice",
            "Brown rice",
            new BigDecimal("1.000"),
            "kg",
            22,
            "out of stock",
            null);

    List<GrocerySubstitutionProposal> result = persister().persistAll(order, List.of(proposal));

    assertThat(result).hasSize(1);
    GrocerySubstitutionProposal saved = result.get(0);
    assertThat(saved.getProposalStatus()).isEqualTo(SubstitutionProposalStatus.PENDING_USER_REVIEW);
    assertThat(saved.getGroceryOrderId()).isEqualTo(order.getId());
    assertThat(saved.getOriginalIngredientMappingKey()).isEqualTo("white rice");
    assertThat(saved.getGroceryOrderLineId()).isEqualTo(order.getLines().get(0).getId());
    assertThat(saved.getSubstituteProductId()).isEqualTo("fake-sku-brown rice");
  }

  @Test
  void persistAll_opaquePayload_landsUnparsed() {
    stubSaveEchoes();
    GroceryOrder order = orderWithLine("white rice");
    SubstitutionProposal opaque =
        new SubstitutionProposal(
            "fake-sku-white rice", "White rice", null, null, null, null, null, "dom differs", null);

    List<GrocerySubstitutionProposal> result = persister().persistAll(order, List.of(opaque));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getProposalStatus()).isEqualTo(SubstitutionProposalStatus.UNPARSED);
  }

  @Test
  void persistAll_publishesOneProposedEventPerProposal() {
    stubSaveEchoes();
    GroceryOrder order = orderWithLine("white rice");
    SubstitutionProposal a =
        new SubstitutionProposal(
            "fake-sku-white rice", "White rice", "sub-a", "Sub A", null, null, null, "oos", null);
    SubstitutionProposal b =
        new SubstitutionProposal(
            "fake-sku-other", "Other", "sub-b", "Sub B", null, null, null, "oos", null);

    persister().persistAll(order, List.of(a, b));

    ArgumentCaptor<SubstitutionProposedEvent> captor =
        ArgumentCaptor.forClass(SubstitutionProposedEvent.class);
    verify(eventPublisher, times(2)).publishEvent(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(e -> assertThat(e.groceryOrderId()).isEqualTo(order.getId()));
  }

  @Test
  void persistAll_emptyOrNull_persistsNothing_publishesNothing() {
    assertThat(persister().persistAll(orderWithLine("k"), List.of())).isEmpty();
    assertThat(persister().persistAll(orderWithLine("k"), null)).isEmpty();
    verify(dataGateway, never()).saveProposal(any());
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void persistAll_neverAutoResolves_statusIsAlwaysPendingOrUnparsed() {
    stubSaveEchoes();
    GroceryOrder order = orderWithLine("white rice");
    SubstitutionProposal parseable =
        new SubstitutionProposal(
            "fake-sku-white rice", "White rice", "sub", "Sub", null, null, null, "oos", null);

    GrocerySubstitutionProposal saved = persister().persistAll(order, List.of(parseable)).get(0);

    // No code path here ever yields ACCEPTED / REJECTED — resolution is a later user decision.
    assertThat(saved.getProposalStatus())
        .isIn(SubstitutionProposalStatus.PENDING_USER_REVIEW, SubstitutionProposalStatus.UNPARSED);
    assertThat(saved.getResolvedAt()).isNull();
    assertThat(saved.getResolvedByUserId()).isNull();
  }
}
