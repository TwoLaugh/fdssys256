package com.example.mealprep.grocery.domain.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.grocery.api.dto.GrocerySubstitutionProposalDto;
import com.example.mealprep.grocery.api.dto.ResolveSubstitutionRequest;
import com.example.mealprep.grocery.domain.entity.GroceryOrder;
import com.example.mealprep.grocery.domain.entity.GrocerySubstitutionProposal;
import com.example.mealprep.grocery.domain.entity.SubstitutionProposalStatus;
import com.example.mealprep.grocery.domain.service.GroceryOrderService;
import com.example.mealprep.grocery.event.GroceryOrderReconciledEvent;
import com.example.mealprep.grocery.exception.OrderHasOutstandingProposalsException;
import com.example.mealprep.grocery.testdata.GroceryTestData;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.testsupport.TestContainersConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real-DB integration test (Testcontainers) for the Tier-3 reconcile gate + reconciliation write
 * (grocery-01f). Verifies: reconcile is BLOCKED while a proposal is pending/unparsed (forced →
 * {@link OrderHasOutstandingProposalsException} 422, GROC-21); resolving all proposals reconciles
 * the order — writing {@code PAID} observations (weight 1.0), adding inventory via the canonical
 * provisions path, firing {@link GroceryOrderReconciledEvent} exactly ONCE; and that a re-reconcile
 * (retry) is idempotent (no second event, no double inventory add).
 */
@SpringBootTest
@Import({TestContainersConfig.class, OrderReconciliationIT.CaptureConfig.class})
@ActiveProfiles("test")
class OrderReconciliationIT {

  @Autowired private GroceryOrderService orderService;
  @Autowired private OrderReconciler reconciler;
  @Autowired private GroceryOrderDataGateway dataGateway;
  @Autowired private ProvisionQueryService provisionQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private Capture capture;

  /**
   * Run a reconciler entry point inside a committed transaction (the prod caller is transactional).
   */
  private boolean inTx(java.util.function.Supplier<Boolean> action) {
    return Boolean.TRUE.equals(
        new TransactionTemplate(transactionManager).execute(t -> action.get()));
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM provision_grocery_import_log");
    jdbcTemplate.update("DELETE FROM provision_inventory_audit");
    jdbcTemplate.update("DELETE FROM provision_inventory");
    jdbcTemplate.update("DELETE FROM provision_supplier_products");
    jdbcTemplate.update("DELETE FROM grocery_substitution_proposals");
    jdbcTemplate.update("DELETE FROM grocery_price_history");
    jdbcTemplate.update("DELETE FROM grocery_order_lines");
    jdbcTemplate.update("DELETE FROM grocery_orders");
    jdbcTemplate.update("DELETE FROM shopping_list_lines");
    jdbcTemplate.update("DELETE FROM shopping_lists");
    capture.reconciled.clear();
  }

  // ---- fixtures ----

  /** Seed a minimal shopping list (the grocery_orders.shopping_list_id FK target) for a user. */
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

  /**
   * Persist a DELIVERED order (one delivered line, paid unit pence set) owned by {@code userId}.
   * Seeded via JDBC (auto-commit) so the FK target rows are committed before the JPA reconcile path
   * reads them — sidesteps JDBC↔JPA connection-visibility ordering.
   */
  private GroceryOrder seedDeliveredOrder(UUID userId, String key) {
    UUID listId = seedList(userId);
    UUID orderId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO grocery_orders (id, user_id, household_id, shopping_list_id, provider_key,"
            + " provider_order_id, status, currency, delivered_at, automation_failure_log,"
            + " trace_id, version, created_at, updated_at) VALUES (?, ?, NULL, ?, 'fake', ?,"
            + " 'DELIVERED', 'GBP', now(), '[]'::jsonb, ?, 0, now(), now())",
        orderId,
        userId,
        listId,
        "fake-order-" + orderId,
        orderId);
    UUID lineId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO grocery_order_lines (id, grocery_order_id, ingredient_mapping_key,"
            + " display_name, provider_product_id, quantity_requested, quantity_unit, pack_size_g,"
            + " pack_count_requested, paid_unit_pence, line_status, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, 1.000, 'kg', 500, 1, 120, 'DELIVERED', now(), now())",
        lineId,
        orderId,
        key,
        "Display " + key,
        "fake-sku-" + key);
    return dataGateway
        .findOrderWithLinesById(orderId)
        .orElseThrow(() -> new IllegalStateException("seeded order not found"));
  }

  private GrocerySubstitutionProposal seedProposal(
      UUID orderId, UUID lineId, SubstitutionProposalStatus status) {
    GrocerySubstitutionProposal proposal =
        GroceryTestData.substitutionProposal()
            .groceryOrderId(orderId)
            .groceryOrderLineId(lineId)
            .proposalStatus(status)
            .build();
    return dataGateway.saveAndFlushProposal(proposal);
  }

  // ---- the gate (422) ----

  @Test
  void reconcile_blockedWhilePending_throws422() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = seedDeliveredOrder(userId, "white rice");
    seedProposal(
        order.getId(),
        order.getLines().get(0).getId(),
        SubstitutionProposalStatus.PENDING_USER_REVIEW);

    assertThatThrownBy(() -> inTx(() -> reconciler.reconcile(order.getId())))
        .isInstanceOf(OrderHasOutstandingProposalsException.class);

    // Auto path is a SILENT no-op while pending — does not reconcile, does not throw.
    assertThat(inTx(() -> reconciler.tryReconcile(order.getId()))).isFalse();
    assertThat(statusOf(order.getId())).isEqualTo("DELIVERED");
    assertThat(capture.reconciled).isEmpty();
  }

  @Test
  void reconcile_blockedWhileUnparsed_throws422() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = seedDeliveredOrder(userId, "white rice");
    seedProposal(
        order.getId(), order.getLines().get(0).getId(), SubstitutionProposalStatus.UNPARSED);

    assertThatThrownBy(() -> inTx(() -> reconciler.reconcile(order.getId())))
        .isInstanceOf(OrderHasOutstandingProposalsException.class);
  }

  // ---- resolve-all → reconcile ----

  @Test
  void resolveAll_thenReconcile_writesPaid_addsInventory_firesReconciledOnce() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = seedDeliveredOrder(userId, "white rice");
    GrocerySubstitutionProposal proposal =
        seedProposal(
            order.getId(),
            order.getLines().get(0).getId(),
            SubstitutionProposalStatus.PENDING_USER_REVIEW);

    // Resolving the last outstanding proposal triggers tryReconcile in the same flow.
    GrocerySubstitutionProposalDto resolved =
        orderService.resolveSubstitution(
            userId,
            new ResolveSubstitutionRequest(proposal.getId(), SubstitutionProposalStatus.ACCEPTED));
    assertThat(resolved.proposalStatus()).isEqualTo(SubstitutionProposalStatus.ACCEPTED);

    // Order reconciled.
    assertThat(statusOf(order.getId())).isEqualTo("RECONCILED");

    // PAID observation (weight 1.0) written for the delivered line.
    Long paidRows =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grocery_price_history WHERE source = 'PAID' AND"
                + " grocery_order_id = ?",
            Long.class,
            order.getId());
    assertThat(paidRows).isEqualTo(1L);

    // Inventory added via the canonical provisions path.
    List<InventoryItemDto> inventory =
        provisionQueryService.getActiveInventoryByMappingKey(userId, "white rice");
    assertThat(inventory).hasSize(1);

    // ReconciledEvent fired exactly ONCE.
    assertThat(capture.reconciled).hasSize(1);
    assertThat(capture.reconciled.get(0).groceryOrderId()).isEqualTo(order.getId());
  }

  @Test
  void reconcile_reRun_isIdempotent_noSecondEvent_noDoubleInventory() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = seedDeliveredOrder(userId, "white rice");
    // No proposals → the gate is clear; reconcile straight away.
    assertThat(inTx(() -> reconciler.tryReconcile(order.getId()))).isTrue();
    assertThat(statusOf(order.getId())).isEqualTo("RECONCILED");
    assertThat(capture.reconciled).hasSize(1);

    int inventoryAfterFirst =
        provisionQueryService.getActiveInventoryByMappingKey(userId, "white rice").size();

    // Re-reconcile (retry): the status==RECONCILED guard short-circuits — no second event, no
    // double inventory add. (And had it slipped through, the order-id orderRef makes the provisions
    // applyGroceryOrder reject the replay as DuplicateGroceryImportException, treated as a no-op.)
    assertThat(inTx(() -> reconciler.tryReconcile(order.getId()))).isFalse();
    assertThat(inTx(() -> reconciler.reconcile(order.getId()))).isFalse();

    assertThat(capture.reconciled).hasSize(1); // still exactly once
    assertThat(provisionQueryService.getActiveInventoryByMappingKey(userId, "white rice"))
        .hasSize(inventoryAfterFirst);
  }

  @Test
  void mixedAcceptReject_allMustResolveBeforeReconcile() {
    UUID userId = UUID.randomUUID();
    GroceryOrder order = seedDeliveredOrder(userId, "white rice");
    UUID lineId = order.getLines().get(0).getId();
    GrocerySubstitutionProposal a =
        seedProposal(order.getId(), lineId, SubstitutionProposalStatus.PENDING_USER_REVIEW);
    GrocerySubstitutionProposal b =
        seedProposal(order.getId(), lineId, SubstitutionProposalStatus.PENDING_USER_REVIEW);

    // Resolving the first (accept) leaves the second pending → NOT reconciled yet.
    orderService.resolveSubstitution(
        userId, new ResolveSubstitutionRequest(a.getId(), SubstitutionProposalStatus.ACCEPTED));
    assertThat(statusOf(order.getId())).isEqualTo("DELIVERED");
    assertThat(capture.reconciled).isEmpty();

    // Resolving the second (reject) clears the gate → reconciled.
    orderService.resolveSubstitution(
        userId, new ResolveSubstitutionRequest(b.getId(), SubstitutionProposalStatus.REJECTED));
    assertThat(statusOf(order.getId())).isEqualTo("RECONCILED");
    assertThat(capture.reconciled).hasSize(1);
  }

  private String statusOf(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM grocery_orders WHERE id = ?", String.class, orderId);
  }

  static class Capture {
    final List<GroceryOrderReconciledEvent> reconciled = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    void onReconciled(GroceryOrderReconciledEvent event) {
      reconciled.add(event);
    }
  }

  @TestConfiguration
  static class CaptureConfig {
    @Bean
    Capture reconciledCapture() {
      return new Capture();
    }
  }
}
