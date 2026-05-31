package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.provisions.api.dto.BatchCookSplitDirective;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.DefrostMethod;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.event.ItemAdjustmentSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates prepared-portion inventory rows when a cook event is flagged {@code isBatchCook}. Per LLD
 * §Flow 1 step 4 (line 611): a cooked batch splits into a FRIDGE row (eaten in the next few days)
 * and a FREEZER row (longer-term, carries the full freezer extension). Both rows are quantity-
 * tracked in {@code "portions"}, {@code source = BATCH_COOK}, {@code source_recipe_id = recipeId}.
 *
 * <p>The directive's {@code fridgePortions} / {@code freezerPortions} pick which rows are created;
 * a zero count skips that row. {@code fridgeMaxDays} / {@code freezerMaxWeeks} are optional caller
 * overrides on top of the splitter defaults ({@value #DEFAULT_FRIDGE_MAX_DAYS} days fridge /
 * {@value #DEFAULT_FREEZER_MAX_WEEKS} weeks freezer). The split writes one {@code actor =
 * COOK_EVENT} audit row per created portion and records a {@code COOK_EVENT} adjustment on the
 * {@link ProvisionEventBatcher} so the cook event's single coalesced {@code
 * ItemQuantityAdjustedEvent} also carries the new portion ids.
 *
 * <p>Invoked inside the {@code applyCookEvent} transaction (the caller owns atomicity); this helper
 * never opens its own transaction.
 */
@Component
class BatchCookSplitter {

  private static final Logger log = LoggerFactory.getLogger(BatchCookSplitter.class);

  static final int DEFAULT_FRIDGE_MAX_DAYS = 4;
  static final int DEFAULT_FREEZER_MAX_WEEKS = 12;
  private static final String PORTIONS_UNIT = "portions";
  private static final String BATCH_CATEGORY = "prepared_meal";
  private static final String FIELD_CREATED = "created";

  private final InventoryItemRepository inventoryItemRepository;
  private final InventoryAuditLogRepository auditLogRepository;
  private final ProvisionEventBatcher eventBatcher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  BatchCookSplitter(
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
   * Apply the split, creating up to two prepared-portion rows. Returns the ids of every row created
   * (empty when the directive splits nothing into either store — which the caller treats as an
   * invalid no-batch directive and rejects upstream). When {@code directive} is null the splitter
   * applies a default whole-batch-to-fridge fallback ({@code servingsCooked} fridge portions), so a
   * {@code isBatchCook} command without an explicit split still produces a usable prepared row.
   */
  List<UUID> split(
      UUID userId,
      UUID recipeId,
      String batchLabel,
      int servingsCooked,
      BatchCookSplitDirective directive) {
    int fridgePortions;
    int freezerPortions;
    Integer fridgeMaxDays;
    Integer freezerMaxWeeks;
    if (directive == null) {
      // No explicit directive — default the whole cooked batch to the fridge.
      fridgePortions = Math.max(servingsCooked, 1);
      freezerPortions = 0;
      fridgeMaxDays = null;
      freezerMaxWeeks = null;
    } else {
      fridgePortions = Math.max(directive.fridgePortions(), 0);
      freezerPortions = Math.max(directive.freezerPortions(), 0);
      fridgeMaxDays = directive.fridgeMaxDays();
      freezerMaxWeeks = directive.freezerMaxWeeks();
    }

    LocalDate today = LocalDate.now(clock);
    String label =
        (batchLabel == null || batchLabel.isBlank()) ? defaultLabel(recipeId) : batchLabel;
    List<UUID> created = new ArrayList<>(2);

    if (fridgePortions > 0) {
      int days = fridgeMaxDays != null ? fridgeMaxDays : DEFAULT_FRIDGE_MAX_DAYS;
      InventoryItem fridge =
          baseRow(userId, recipeId, label, fridgePortions)
              .storageLocation(StorageLocation.FRIDGE)
              .expiryDate(today.plusDays(days))
              .build();
      created.add(persist(userId, fridge));
    }

    if (freezerPortions > 0) {
      int weeks = freezerMaxWeeks != null ? freezerMaxWeeks : DEFAULT_FREEZER_MAX_WEEKS;
      LocalDate frozenAt = today;
      InventoryItem freezer =
          baseRow(userId, recipeId, label, freezerPortions)
              .storageLocation(StorageLocation.FREEZER)
              .expiryDate(frozenAt.plusWeeks(weeks))
              .frozenAt(frozenAt)
              .maxFreezeWeeks(weeks)
              .defrostMethod(DefrostMethod.OVERNIGHT_FRIDGE)
              .sourceRecipeId(recipeId)
              .build();
      created.add(persist(userId, freezer));
    }

    log.info(
        "batch-cook split userId={} recipeId={} fridgePortions={} freezerPortions={} createdRows={}",
        userId,
        recipeId,
        fridgePortions,
        freezerPortions,
        created.size());
    return created;
  }

  private InventoryItem.InventoryItemBuilder baseRow(
      UUID userId, UUID recipeId, String label, int portions) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name(label)
        .category(BATCH_CATEGORY)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(BigDecimal.valueOf(portions))
        .unit(PORTIONS_UNIT)
        .isStaple(false)
        .source(ItemSource.BATCH_COOK)
        .sourceRef(recipeId == null ? null : recipeId.toString())
        .sourceRecipeId(recipeId)
        .itemStatus(ItemLifecycleStatus.ACTIVE);
  }

  private UUID persist(UUID userId, InventoryItem row) {
    InventoryItem saved = inventoryItemRepository.saveAndFlush(row);
    Instant now = Instant.now(clock);
    JsonNode snapshot =
        objectMapper.valueToTree(
            java.util.Map.of(
                "name", saved.getName(),
                "storageLocation", saved.getStorageLocation().name(),
                "quantity", saved.getQuantity(),
                "unit", saved.getUnit(),
                "source", saved.getSource().name()));
    auditLogRepository.save(
        new InventoryAuditLog(
            UUID.randomUUID(),
            saved.getId(),
            userId,
            AuditActor.COOK_EVENT,
            null,
            FIELD_CREATED,
            objectMapper.nullNode(),
            snapshot,
            now));
    eventBatcher.recordAdjustment(userId, saved.getId(), ItemAdjustmentSource.COOK_EVENT, null);
    return saved.getId();
  }

  private static String defaultLabel(UUID recipeId) {
    return recipeId == null ? "Batch cook" : "Batch cook (" + recipeId + ")";
  }
}
