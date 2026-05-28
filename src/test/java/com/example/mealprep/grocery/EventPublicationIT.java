package com.example.mealprep.grocery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.grocery.api.dto.CreateOrderRequest;
import com.example.mealprep.grocery.api.dto.GroceryOrderDto;
import com.example.mealprep.grocery.api.dto.PlaceOrderRequest;
import com.example.mealprep.grocery.api.dto.ProviderConnectionRequest;
import com.example.mealprep.grocery.api.dto.QuoteRequest;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider;
import com.example.mealprep.grocery.event.GroceryOrderConfirmedEvent;
import com.example.mealprep.grocery.event.GroceryOrderDeliveredEvent;
import com.example.mealprep.grocery.event.GroceryOrderLifecycleEvent;
import com.example.mealprep.grocery.event.GroceryOrderQuotedEvent;
import com.example.mealprep.grocery.event.GroceryOrderReconciledEvent;
import com.example.mealprep.grocery.event.SubstitutionProposedEvent;
import com.example.mealprep.grocery.event.SubstitutionResolvedEvent;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.grocery.testsupport.FakeGroceryProviderConfig;
import com.example.mealprep.testsupport.TestContainersConfig;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Real-DB integration test (Testcontainers) for the Tier-3 event publication contract (grocery-01f
 * + 01e). Drives the lifecycle through the public {@link GroceryOrderService} against the {@link
 * FakeGroceryProvider} and asserts via {@code @TransactionalEventListener(AFTER_COMMIT)} captors
 * that: each lifecycle event fires exactly once AFTER commit; the sealed {@link
 * GroceryOrderLifecycleEvent} hierarchy dispatches to subtype-specific listeners; one {@link
 * SubstitutionProposedEvent} fires per persisted proposal at delivery; and {@link
 * SubstitutionResolvedEvent} carries the user's ACCEPT vs REJECT decision.
 */
@SpringBootTest
@Import({
  TestContainersConfig.class,
  FakeGroceryProviderConfig.class,
  EventPublicationIT.CaptureConfig.class
})
@ActiveProfiles("test")
class EventPublicationIT {

  private static final String PROVIDER = "fake";

  @Autowired private GroceryOrderService orderService;
  @Autowired private FakeGroceryProvider fakeGroceryProvider;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private Capture capture;

  @BeforeEach
  void resetProvider() {
    fakeGroceryProvider.reset();
  }

  @AfterEach
  void cleanup() {
    fakeGroceryProvider.reset();
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    jdbcTemplate.update("DELETE FROM grocery_substitution_proposals");
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    jdbcTemplate.update("DELETE FROM grocery_order_lines");
    jdbcTemplate.update("DELETE FROM grocery_orders");
    jdbcTemplate.update("DELETE FROM grocery_provider_state");
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    capture.clear();
  }

  // ---- fixtures ----

  private UUID seedList(UUID userId) {
    UUID listId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO shopping_lists (id, user_id, household_id, plan_id, plan_generation,"
            + " generated_at, estimated_total_currency, stale_ingredient_count,"
            + " pantry_tracking_enabled, version, created_at, updated_at)"
            + " VALUES (?, ?, NULL, ?, 1, now(), 'GBP', 0, false, 0, now(), now())",
        listId,
        userId,
        UUID.randomUUID());
    return listId;
  }

  private void seedLine(UUID listId, String key, String displayName) {
    jdbcTemplate.update(
        "INSERT INTO shopping_list_lines (id, shopping_list_id, ingredient_mapping_key,"
            + " display_name, requested_quantity, requested_unit, suggested_pack_size_g,"
            + " suggested_pack_count, line_type, is_stale_estimate, fulfilment_status, created_at,"
            + " updated_at) VALUES (?, ?, ?, ?, 1.000, 'kg', 500, 1, 'PLANNED_DEMAND', false,"
            + " 'UNFILLED', now(), now())",
        UUID.randomUUID(),
        listId,
        key,
        displayName);
  }

  private void enableProvider(UUID userId) {
    orderService.upsertProviderConnection(
        userId, new ProviderConnectionRequest(PROVIDER, true, false, 50));
  }

  /** Drive create → quote → place → confirm; returns the order id (status CONFIRMED). */
  private UUID confirmedOrder(UUID userId, String key) {
    enableProvider(userId);
    UUID listId = seedList(userId);
    seedLine(listId, key, "Display " + key);
    GroceryOrderDto draft =
        orderService.createDraft(userId, new CreateOrderRequest(listId, PROVIDER));
    orderService.quote(userId, new QuoteRequest(draft.id()));
    orderService.placeOrder(userId, new PlaceOrderRequest(draft.id()));
    orderService.markUserConfirmed(userId, draft.id());
    return draft.id();
  }

  // ---- tests ----

  @Test
  void noSubstitutionDelivery_firesEachLifecycleEventOnce_andReconcilesOnce() {
    UUID userId = UUID.randomUUID();
    UUID orderId = confirmedOrder(userId, "white rice");

    // markDelivered with no substitutions → straight to RECONCILED.
    orderService.markDelivered(userId, orderId);

    assertThat(capture.quoted).hasSize(1);
    assertThat(capture.confirmed).hasSize(1);
    assertThat(capture.delivered).hasSize(1);
    assertThat(capture.reconciled).hasSize(1);
    // No proposals surfaced → none proposed, none resolved.
    assertThat(capture.proposed).isEmpty();
    assertThat(capture.resolved).isEmpty();

    // Sealed-hierarchy dispatch: the base-type listener saw every lifecycle subtype.
    assertThat(capture.lifecycleTypes)
        .contains(
            GroceryOrderQuotedEvent.class.getSimpleName(),
            GroceryOrderConfirmedEvent.class.getSimpleName(),
            GroceryOrderDeliveredEvent.class.getSimpleName(),
            GroceryOrderReconciledEvent.class.getSimpleName());
  }

  @Test
  void deliveryWithSubstitution_resolveAccept_firesProposedThenResolvedAccepted_thenReconciled() {
    UUID userId = UUID.randomUUID();
    UUID orderId = confirmedOrder(userId, "white rice");

    // Provider reports DELIVERED with one substitution → persisted PENDING_USER_REVIEW.
    fakeGroceryProvider.setDelivered(true);
    fakeGroceryProvider.setSubstitutions(
        List.of(GroceryTestData.providerSubstitution("white rice", "Brown rice")));
    orderService.refreshStatus(userId, orderId);

    assertThat(capture.delivered).hasSize(1);
    assertThat(capture.proposed).hasSize(1); // one SubstitutionProposedEvent per proposal
    assertThat(capture.reconciled).isEmpty(); // blocked while pending

    UUID proposalId = capture.proposed.get(0).proposalId();
    orderService.resolveSubstitution(
        userId, new ResolveSubstitutionRequest(proposalId, SubstitutionProposalStatus.ACCEPTED));

    // One decision-carrying SubstitutionResolvedEvent (ACCEPTED), then reconciliation once.
    assertThat(capture.resolved).hasSize(1);
    assertThat(capture.resolved.get(0).decision()).isEqualTo(SubstitutionProposalStatus.ACCEPTED);
    assertThat(capture.reconciled).hasSize(1);
  }

  @Test
  void resolveReject_firesResolvedRejected() {
    UUID userId = UUID.randomUUID();
    UUID orderId = confirmedOrder(userId, "white rice");

    fakeGroceryProvider.setDelivered(true);
    fakeGroceryProvider.setSubstitutions(
        List.of(GroceryTestData.providerSubstitution("white rice", "Brown rice")));
    orderService.refreshStatus(userId, orderId);

    UUID proposalId = capture.proposed.get(0).proposalId();
    orderService.resolveSubstitution(
        userId, new ResolveSubstitutionRequest(proposalId, SubstitutionProposalStatus.REJECTED));

    assertThat(capture.resolved).hasSize(1);
    assertThat(capture.resolved.get(0).decision()).isEqualTo(SubstitutionProposalStatus.REJECTED);
    // All proposals resolved → reconciled.
    assertThat(capture.reconciled).hasSize(1);
  }

  // ---- captors (AFTER_COMMIT) ----

  static class Capture {
    final List<GroceryOrderQuotedEvent> quoted = new CopyOnWriteArrayList<>();
    final List<GroceryOrderConfirmedEvent> confirmed = new CopyOnWriteArrayList<>();
    final List<GroceryOrderDeliveredEvent> delivered = new CopyOnWriteArrayList<>();
    final List<GroceryOrderReconciledEvent> reconciled = new CopyOnWriteArrayList<>();
    final List<SubstitutionProposedEvent> proposed = new CopyOnWriteArrayList<>();
    final List<SubstitutionResolvedEvent> resolved = new CopyOnWriteArrayList<>();
    final List<String> lifecycleTypes = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void onQuoted(GroceryOrderQuotedEvent e) {
      quoted.add(e);
    }

    @TransactionalEventListener
    void onConfirmed(GroceryOrderConfirmedEvent e) {
      confirmed.add(e);
    }

    @TransactionalEventListener
    void onDelivered(GroceryOrderDeliveredEvent e) {
      delivered.add(e);
    }

    @TransactionalEventListener
    void onReconciled(GroceryOrderReconciledEvent e) {
      reconciled.add(e);
    }

    @TransactionalEventListener
    void onProposed(SubstitutionProposedEvent e) {
      proposed.add(e);
    }

    @TransactionalEventListener
    void onResolved(SubstitutionResolvedEvent e) {
      resolved.add(e);
    }

    /** Sealed-hierarchy dispatch: one listener on the base type sees every subtype. */
    @TransactionalEventListener
    void onAnyLifecycle(GroceryOrderLifecycleEvent e) {
      lifecycleTypes.add(e.getClass().getSimpleName());
    }

    void clear() {
      quoted.clear();
      confirmed.clear();
      delivered.clear();
      reconciled.clear();
      proposed.clear();
      resolved.clear();
      lifecycleTypes.clear();
    }
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    Capture groceryEventCapture() {
      return new Capture();
    }
  }
}
