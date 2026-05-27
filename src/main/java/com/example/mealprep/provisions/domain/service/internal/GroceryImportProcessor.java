package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.core.ingredient.IngredientMappingKeys;
import com.example.mealprep.provisions.api.dto.GroceryImportResultDto;
import com.example.mealprep.provisions.api.dto.GroceryOrderImportCommand;
import com.example.mealprep.provisions.api.dto.GroceryOrderLine;
import com.example.mealprep.provisions.api.dto.GroceryOrderSubstitution;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.SupplierProductDto;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.api.mapper.SupplierProductMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.ProvisionGroceryImportLog;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.SubstitutionRecord;
import com.example.mealprep.provisions.domain.entity.SupplierProduct;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.repository.ProvisionGroceryImportLogRepository;
import com.example.mealprep.provisions.domain.repository.SupplierProductRepository;
import com.example.mealprep.provisions.exception.DuplicateGroceryImportException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-line orchestrator for the grocery-import flow (LLD §Flow 2 line 627-641). Invoked from {@link
 * com.example.mealprep.provisions.domain.service.ProvisionUpdateService#applyGroceryOrder} inside
 * its {@code @Transactional} boundary — atomicity (LLD line 640) is the caller's responsibility;
 * this class throws on any per-line failure to roll the whole import back.
 *
 * <p>Steps per LLD line 631-640: (1) idempotency check via the log table (LLD divergence in 01h —
 * the dedicated log table replaces the inventory-side index for substitution-only orders); (2)
 * per-line supplier-product upsert + expiry-aware merge-or-create on inventory; (3)
 * substitution-history append on the cached {@code orderedProductId} (advisory — un-cached
 * orderedProductId emits a warning, not an exception); (4) coalesced {@code
 * ItemAddedFromGroceryEvent} at {@code AFTER_COMMIT} via {@link ProvisionEventBatcher}.
 *
 * <p>TODO(provisions-01i): when a staple gets replenished here, transition its {@code StapleStatus}
 * OUT → STOCKED. Deferred to the full {@code StapleStateTransitioner} ticket.
 */
@Component
class GroceryImportProcessor {

  private static final Logger log = LoggerFactory.getLogger(GroceryImportProcessor.class);

  private static final String FIELD_QUANTITY = "quantity";

  private final InventoryItemRepository inventoryItemRepository;
  private final SupplierProductRepository supplierProductRepository;
  private final ProvisionGroceryImportLogRepository importLogRepository;
  private final InventoryAuditLogRepository auditLogRepository;
  private final ExpiryInferenceService expiryInference;
  private final ProvisionEventBatcher eventBatcher;
  private final InventoryItemMapper inventoryMapper;
  private final SupplierProductMapper supplierMapper;
  private final ObjectMapper objectMapper;

  GroceryImportProcessor(
      InventoryItemRepository inventoryItemRepository,
      SupplierProductRepository supplierProductRepository,
      ProvisionGroceryImportLogRepository importLogRepository,
      InventoryAuditLogRepository auditLogRepository,
      ExpiryInferenceService expiryInference,
      ProvisionEventBatcher eventBatcher,
      InventoryItemMapper inventoryMapper,
      SupplierProductMapper supplierMapper,
      ObjectMapper objectMapper) {
    this.inventoryItemRepository = inventoryItemRepository;
    this.supplierProductRepository = supplierProductRepository;
    this.importLogRepository = importLogRepository;
    this.auditLogRepository = auditLogRepository;
    this.expiryInference = expiryInference;
    this.eventBatcher = eventBatcher;
    this.inventoryMapper = inventoryMapper;
    this.supplierMapper = supplierMapper;
    this.objectMapper = objectMapper;
  }

  GroceryImportResultDto process(UUID userId, GroceryOrderImportCommand command, AuditActor actor) {
    ItemSource source =
        "tesco".equalsIgnoreCase(command.supplier())
            ? ItemSource.TESCO_ORDER
            : ItemSource.OTHER_SHOP;

    // Step 1 — idempotency check (race-safe via composite-PK constraint on the log table).
    if (importLogRepository.existsByIdUserIdAndIdSourceAndIdSourceRef(
        userId, source, command.orderRef())) {
      throw new DuplicateGroceryImportException(userId, source, command.orderRef());
    }
    importLogRepository.save(
        new ProvisionGroceryImportLog(
            userId, source, command.orderRef(), command.traceId(), Instant.now()));

    UUID traceId = command.traceId() != null ? command.traceId() : UUID.randomUUID();
    List<InventoryItemDto> added = new ArrayList<>();
    List<InventoryItemDto> merged = new ArrayList<>();
    List<SupplierProductDto> supplierUpdates = new ArrayList<>();
    List<String> warnings = new ArrayList<>();

    // Step 2 — per-line processing.
    for (GroceryOrderLine line : command.lines()) {
      // Normalise the mapping key ONCE (core-03): used for the supplier upsert, the inventory
      // merge-lookup, and the inventory-row create. A raw lookup against normalised rows would
      // silently fail to merge → duplicate inventory rows, so the lookup MUST use the same key.
      String mappingKey = IngredientMappingKeys.normalise(line.ingredientMappingKey());

      supplierUpdates.add(
          upsertSupplierProduct(command.supplier(), command.deliveredOn(), line, mappingKey));

      StorageLocation location = inferLocation(line.category());
      LocalDate expiry =
          expiryInference
              .inferExpiry(mappingKey, line.category(), command.deliveredOn())
              .orElse(null);

      Optional<InventoryItem> existing =
          mappingKey == null
              ? Optional.empty()
              : inventoryItemRepository
                  .findOneActiveByUserIdAndMappingKeyAndStorageLocationAndExpiryDate(
                      userId, mappingKey, location, expiry);

      Instant now = Instant.now();
      if (existing.isPresent()) {
        InventoryItem item = existing.get();
        BigDecimal prevQty = item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
        BigDecimal nextQty = prevQty.add(line.quantity());
        item.setQuantity(nextQty);
        if (line.pricePaid() != null) {
          BigDecimal prevCost = item.getCostPaid() != null ? item.getCostPaid() : BigDecimal.ZERO;
          item.setCostPaid(prevCost.add(line.pricePaid()));
        }
        InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
        recordAudit(saved.getId(), userId, actor, prevQty, nextQty, now);
        eventBatcher.recordItemAddedFromGrocery(
            userId, saved.getId(), command.supplier(), command.orderRef(), traceId);
        merged.add(inventoryMapper.toDto(saved));
      } else {
        InventoryItem item =
            createNewInventoryRow(
                userId, line, mappingKey, location, expiry, source, command.orderRef());
        InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
        recordAudit(saved.getId(), userId, actor, BigDecimal.ZERO, saved.getQuantity(), now);
        eventBatcher.recordItemAddedFromGrocery(
            userId, saved.getId(), command.supplier(), command.orderRef(), traceId);
        added.add(inventoryMapper.toDto(saved));
      }
    }

    // Step 3 — substitutions (advisory; un-cached supplier product emits a warning).
    if (command.substitutions() != null) {
      for (GroceryOrderSubstitution sub : command.substitutions()) {
        Optional<SupplierProduct> orderedOpt =
            supplierProductRepository.findBySupplierAndProductId(
                command.supplier(), sub.orderedProductId());
        if (orderedOpt.isPresent()) {
          appendSubstitutionRecord(orderedOpt.get(), sub, command.deliveredOn());
        } else {
          String warning =
              "supplier product orderedProductId="
                  + sub.orderedProductId()
                  + " not cached; substitution history skipped";
          warnings.add(warning);
          log.info(
              "supplier product not cached for orderedProductId={}; substitution history skipped",
              sub.orderedProductId());
        }
      }
    }

    return new GroceryImportResultDto(added, merged, supplierUpdates, warnings);
  }

  private SupplierProductDto upsertSupplierProduct(
      String supplier, LocalDate deliveredOn, GroceryOrderLine line, String mappingKey) {
    Optional<SupplierProduct> existing =
        supplierProductRepository.findBySupplierAndProductId(supplier, line.productId());
    SupplierProduct sp;
    if (existing.isPresent()) {
      sp = existing.get();
      sp.setName(line.name());
      sp.setPrice(line.pricePaid());
      sp.setUnit(line.unit());
      sp.setPackSizeG(line.packSizeG());
      sp.setCategory(line.category());
      sp.setLastChecked(deliveredOn);
      if (mappingKey != null) {
        sp.setIngredientMappingKey(mappingKey);
      }
      // clubcardPrice left intact — GroceryOrderLine doesn't carry it in v1.
    } else {
      sp =
          SupplierProduct.builder()
              .id(UUID.randomUUID())
              .productId(line.productId())
              .supplier(supplier)
              .name(line.name())
              .price(line.pricePaid())
              .unit(line.unit())
              .packSizeG(line.packSizeG())
              .category(line.category())
              .lastChecked(deliveredOn)
              .ingredientMappingKey(mappingKey)
              .substitutionHistory(List.of())
              .build();
    }
    SupplierProduct saved = supplierProductRepository.saveAndFlush(sp);
    return supplierMapper.toDto(saved);
  }

  private static StorageLocation inferLocation(String category) {
    if (category == null) {
      return StorageLocation.CUPBOARD;
    }
    String c = category.toLowerCase(Locale.ROOT);
    if (c.equals("frozen") || c.equals("freezer")) {
      return StorageLocation.FREEZER;
    }
    if (c.equals("fridge") || c.equals("dairy") || c.equals("fresh")) {
      return StorageLocation.FRIDGE;
    }
    return StorageLocation.CUPBOARD;
  }

  private InventoryItem createNewInventoryRow(
      UUID userId,
      GroceryOrderLine line,
      String mappingKey,
      StorageLocation location,
      LocalDate expiry,
      ItemSource source,
      String orderRef) {
    return InventoryItem.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .name(line.name())
        .category(line.category() != null ? line.category() : "uncategorised")
        .storageLocation(location)
        .trackingMode(TrackingMode.QUANTITY)
        .quantity(line.quantity())
        .unit(line.unit())
        .costPaid(line.pricePaid())
        .isStaple(false)
        .expiryDate(expiry)
        .ingredientMappingKey(mappingKey)
        .source(source)
        .sourceRef(orderRef)
        .itemStatus(ItemLifecycleStatus.ACTIVE)
        .build();
  }

  private void recordAudit(
      UUID itemId,
      UUID userId,
      AuditActor actor,
      BigDecimal prevQty,
      BigDecimal nextQty,
      Instant now) {
    auditLogRepository.save(
        new InventoryAuditLog(
            UUID.randomUUID(),
            itemId,
            userId,
            actor,
            null,
            FIELD_QUANTITY,
            objectMapper.valueToTree(Map.of(FIELD_QUANTITY, prevQty)),
            objectMapper.valueToTree(Map.of(FIELD_QUANTITY, nextQty)),
            now));
  }

  private void appendSubstitutionRecord(
      SupplierProduct orderedProduct, GroceryOrderSubstitution sub, LocalDate deliveredOn) {
    // Compute the new history list BEFORE mutating; replaceChildren flush-trap from round-6.
    List<SubstitutionRecord> previous =
        orderedProduct.getSubstitutionHistory() == null
            ? List.of()
            : orderedProduct.getSubstitutionHistory();
    List<SubstitutionRecord> next = new ArrayList<>(previous);
    next.add(new SubstitutionRecord(deliveredOn, sub.substitutedProductId(), true, sub.reason()));
    orderedProduct.setSubstitutionHistory(List.copyOf(next));
    supplierProductRepository.saveAndFlush(orderedProduct);
  }
}
