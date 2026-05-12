package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.provisions.api.dto.UnderflowFlagDto;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FIFO-by-expiry inventory deduction helper. Loads active rows for {@code (userId,
 * ingredientMappingKey)} ordered by expiry-asc, walks them decrementing quantity, and floors at
 * zero. Writes audit rows + records adjustments on the {@link ProvisionEventBatcher} for AFTER
 * COMMIT event publication.
 *
 * <p>See LLD line 44 + line 609. Unit mismatches with no conversion log WARN + emit an underflow
 * flag for that row, then continue to the next row (no decrement applied). The full {@code
 * UnitConverter} ships in a follow-up — 01g's assumption is that the planner sends canonical units.
 */
@Component
class InventoryDeductionEngine {

  private static final Logger log = LoggerFactory.getLogger(InventoryDeductionEngine.class);

  private final InventoryItemRepository inventoryItemRepository;
  private final InventoryAuditLogRepository auditLogRepository;
  private final ProvisionEventBatcher eventBatcher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  InventoryDeductionEngine(
      InventoryItemRepository inventoryItemRepository,
      InventoryAuditLogRepository auditLogRepository,
      ProvisionEventBatcher eventBatcher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.inventoryItemRepository = inventoryItemRepository;
    this.auditLogRepository = auditLogRepository;
    this.eventBatcher = eventBatcher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Walk active rows for {@code (userId, ingredientMappingKey)} in expiry-ASC order and decrement
   * up to {@code requested}. Returns the IDs of decremented and exhausted rows, plus any underflow
   * flags. Strict mode is enforced by the caller — this method always returns underflows; the
   * caller throws when {@code strict == true}.
   */
  DeductionOutcome deduct(
      UUID userId,
      String ingredientMappingKey,
      BigDecimal requested,
      String requestedUnit,
      UUID traceId) {
    List<InventoryItem> rows =
        inventoryItemRepository.findActiveByMappingKeyOrderByExpiryAsc(
            userId, ingredientMappingKey);

    BigDecimal remaining = requested == null ? BigDecimal.ZERO : requested;
    Set<UUID> deductedItemIds = new LinkedHashSet<>();
    Set<UUID> exhaustedItemIds = new LinkedHashSet<>();
    List<UnderflowFlagDto> underflows = new ArrayList<>();
    Instant now = Instant.now(clock);

    for (InventoryItem row : rows) {
      if (remaining.signum() <= 0) {
        break;
      }
      if (row.getUnit() != null
          && requestedUnit != null
          && !Objects.equals(row.getUnit(), requestedUnit)) {
        log.warn(
            "deduction unit mismatch userId={} key={} rowUnit={} requestedUnit={} —"
                + " skipping row, emitting underflow",
            userId,
            ingredientMappingKey,
            row.getUnit(),
            requestedUnit);
        // Unit-mismatch row contributes nothing — fall through to underflow accounting at the end.
        continue;
      }
      BigDecimal rowQty = row.getQuantity() == null ? BigDecimal.ZERO : row.getQuantity();
      if (rowQty.signum() <= 0) {
        continue;
      }
      BigDecimal take = rowQty.min(remaining);
      BigDecimal next = rowQty.subtract(take);
      row.setQuantity(next);
      boolean exhausted = next.signum() == 0;
      boolean wasStapleAndStocked = row.isStaple() && row.getStatus() == StapleStatus.STOCKED;
      if (exhausted) {
        row.setItemStatus(ItemLifecycleStatus.EXHAUSTED);
        if (wasStapleAndStocked) {
          row.setStatus(StapleStatus.OUT);
        }
      }
      inventoryItemRepository.saveAndFlush(row);
      auditLogRepository.save(
          new InventoryAuditLog(
              UUID.randomUUID(),
              row.getId(),
              userId,
              AuditActor.COOK_EVENT,
              null,
              "quantity",
              objectMapper.valueToTree(Map.of("quantity", rowQty)),
              objectMapper.valueToTree(Map.of("quantity", next)),
              now));

      deductedItemIds.add(row.getId());
      if (exhausted) {
        exhaustedItemIds.add(row.getId());
      }
      eventBatcher.recordAdjustment(userId, row.getId(), ItemAdjustmentSource.COOK_EVENT, traceId);
      if (exhausted && row.isStaple()) {
        eventBatcher.recordRanOut(userId, row.getId(), ingredientMappingKey, true, traceId);
      }
      remaining = remaining.subtract(take);
    }

    if (remaining.signum() > 0) {
      BigDecimal available = requested == null ? BigDecimal.ZERO : requested.subtract(remaining);
      underflows.add(new UnderflowFlagDto(ingredientMappingKey, requested, available));
    }
    return new DeductionOutcome(
        List.copyOf(deductedItemIds), List.copyOf(exhaustedItemIds), List.copyOf(underflows));
  }

  /** Result tuple — IDs of decremented rows, exhausted rows, plus any underflow flags. */
  record DeductionOutcome(
      List<UUID> deductedItemIds, List<UUID> exhaustedItemIds, List<UnderflowFlagDto> underflows) {}
}
