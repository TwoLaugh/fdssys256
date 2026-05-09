package com.example.mealprep.provisions.domain.service.internal;

import com.example.mealprep.provisions.api.dto.CreateInventoryItemRequest;
import com.example.mealprep.provisions.api.dto.FreezerExtensionDto;
import com.example.mealprep.provisions.api.dto.InventoryItemDto;
import com.example.mealprep.provisions.api.dto.InventorySearchCriteria;
import com.example.mealprep.provisions.api.dto.UpdateInventoryItemRequest;
import com.example.mealprep.provisions.api.mapper.InventoryItemMapper;
import com.example.mealprep.provisions.domain.entity.AuditActor;
import com.example.mealprep.provisions.domain.entity.DefrostMethod;
import com.example.mealprep.provisions.domain.entity.InventoryAuditLog;
import com.example.mealprep.provisions.domain.entity.InventoryItem;
import com.example.mealprep.provisions.domain.entity.ItemLifecycleStatus;
import com.example.mealprep.provisions.domain.entity.ItemSource;
import com.example.mealprep.provisions.domain.entity.StapleStatus;
import com.example.mealprep.provisions.domain.entity.StorageLocation;
import com.example.mealprep.provisions.domain.entity.TrackingMode;
import com.example.mealprep.provisions.domain.repository.InventoryAuditLogRepository;
import com.example.mealprep.provisions.domain.repository.InventoryItemRepository;
import com.example.mealprep.provisions.domain.service.ProvisionQueryService;
import com.example.mealprep.provisions.domain.service.ProvisionUpdateService;
import com.example.mealprep.provisions.event.InventoryItemUpsertedEvent;
import com.example.mealprep.provisions.exception.InventoryItemNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of {@link ProvisionQueryService} and {@link ProvisionUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED (top-level transactions). The
 * update path field-diffs — one audit row per genuinely changed field; if the request is a no-op
 * the row stays unmodified, no audit row is written, no event is published, and the
 * {@code @Version} does not bump.
 *
 * <p>{@link InventoryItemUpsertedEvent} is published synchronously inside the transaction; the
 * event is captured by {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners — none
 * in 01a.
 */
@Service
public class ProvisionServiceImpl implements ProvisionQueryService, ProvisionUpdateService {

  private static final Logger log = LoggerFactory.getLogger(ProvisionServiceImpl.class);

  private static final String MDC_TRACE_ID = "traceId";

  static final String FIELD_CREATED = "created";
  static final String FIELD_NAME = "name";
  static final String FIELD_CATEGORY = "category";
  static final String FIELD_STORAGE_LOCATION = "storageLocation";
  static final String FIELD_TRACKING_MODE = "trackingMode";
  static final String FIELD_QUANTITY = "quantity";
  static final String FIELD_UNIT = "unit";
  static final String FIELD_COST_PAID = "costPaid";
  static final String FIELD_STATUS = "status";
  static final String FIELD_IS_STAPLE = "isStaple";
  static final String FIELD_EXPIRY_DATE = "expiryDate";
  static final String FIELD_INGREDIENT_MAPPING_KEY = "ingredientMappingKey";
  static final String FIELD_NOTES = "notes";
  static final String FIELD_SOURCE = "source";
  static final String FIELD_SOURCE_REF = "sourceRef";
  static final String FIELD_ITEM_STATUS = "itemStatus";
  static final String FIELD_FREEZER_EXTENSION = "freezerExtension";

  private final InventoryItemRepository inventoryItemRepository;
  private final InventoryAuditLogRepository auditLogRepository;
  private final InventoryItemMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ProvisionServiceImpl(
      InventoryItemRepository inventoryItemRepository,
      InventoryAuditLogRepository auditLogRepository,
      InventoryItemMapper mapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.inventoryItemRepository = inventoryItemRepository;
    this.auditLogRepository = auditLogRepository;
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<InventoryItemDto> getInventoryItem(UUID itemId, UUID requestingUserId) {
    return inventoryItemRepository.findByIdAndUserId(itemId, requestingUserId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<InventoryItemDto> listActiveInventory(
      UUID userId, InventorySearchCriteria criteria, Pageable pageable) {
    InventorySearchCriteria effective =
        criteria == null ? InventorySearchCriteria.none() : criteria;
    return inventoryItemRepository
        .findActiveForUser(
            userId,
            ItemLifecycleStatus.ACTIVE,
            effective.storageLocation(),
            effective.isStaple(),
            pageable)
        .map(mapper::toDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public InventoryItemDto createInventoryItem(
      UUID userId, CreateInventoryItemRequest request, AuditActor actor) {
    Instant now = Instant.now(clock);
    UUID itemId = UUID.randomUUID();

    InventoryItem item =
        InventoryItem.builder()
            .id(itemId)
            .userId(userId)
            .name(request.name())
            .category(request.category())
            .storageLocation(request.storageLocation())
            .trackingMode(request.trackingMode())
            .quantity(request.quantity())
            .unit(request.unit())
            .costPaid(request.costPaid())
            .status(request.status())
            .isStaple(request.isStaple())
            .expiryDate(request.expiryDate())
            .ingredientMappingKey(request.ingredientMappingKey())
            .notes(request.notes())
            .source(request.source())
            .sourceRef(request.sourceRef())
            .itemStatus(ItemLifecycleStatus.ACTIVE)
            .build();
    applyFreezerExtension(item, request.freezerExtension());

    // saveAndFlush so {@code @CreationTimestamp} ({@code createdAt}/{@code updatedAt}) and the
    // {@code @Version} bump materialise before we map to DTO; otherwise the response carries null
    // timestamps and the OpenAPI schema rejects the response.
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);

    JsonNode snapshot = toSnapshotJson(saved);
    auditLogRepository.save(
        new InventoryAuditLog(
            UUID.randomUUID(),
            saved.getId(),
            userId,
            actor,
            actor == AuditActor.USER ? userId : null,
            FIELD_CREATED,
            objectMapper.nullNode(),
            snapshot,
            now));

    eventPublisher.publishEvent(
        new InventoryItemUpsertedEvent(saved.getId(), userId, actor, currentTraceId(), now));
    log.info("inventory item created itemId={} userId={} actor={}", saved.getId(), userId, actor);
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public InventoryItemDto updateInventoryItem(
      UUID itemId, UUID requestingUserId, UpdateInventoryItemRequest request) {
    InventoryItem item =
        inventoryItemRepository
            .findByIdAndUserId(itemId, requestingUserId)
            .orElseThrow(() -> new InventoryItemNotFoundException(itemId));

    // Optimistic-lock pre-check: surface 409 immediately rather than waiting for the
    // increment-on-flush, which only fires when fields are actually dirtied.
    if (item.getVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          InventoryItem.class, itemId);
    }

    Snapshot before = Snapshot.of(item);

    item.setName(request.name());
    item.setCategory(request.category());
    item.setStorageLocation(request.storageLocation());
    item.setTrackingMode(request.trackingMode());
    item.setQuantity(request.quantity());
    item.setUnit(request.unit());
    item.setCostPaid(request.costPaid());
    item.setStatus(request.status());
    item.setStaple(request.isStaple());
    item.setExpiryDate(request.expiryDate());
    item.setIngredientMappingKey(request.ingredientMappingKey());
    item.setNotes(request.notes());
    item.setSource(request.source());
    item.setSourceRef(request.sourceRef());
    item.setItemStatus(request.itemStatus());
    applyFreezerExtension(item, request.freezerExtension());

    Snapshot after = Snapshot.of(item);
    Set<String> changedFields = before.diff(after);

    if (changedFields.isEmpty()) {
      // No-op PUT: no audit row, no event, no version bump.
      log.info(
          "inventory item PUT was a no-op itemId={} userId={} version={}",
          itemId,
          requestingUserId,
          item.getVersion());
      return mapper.toDto(item);
    }

    Instant now = Instant.now(clock);
    writeAuditRows(item.getId(), requestingUserId, changedFields, before, after, now);

    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    eventPublisher.publishEvent(
        new InventoryItemUpsertedEvent(
            saved.getId(), requestingUserId, AuditActor.USER, currentTraceId(), now));
    log.info(
        "inventory item updated itemId={} userId={} fieldsChanged={} version={}",
        saved.getId(),
        requestingUserId,
        changedFields,
        saved.getVersion());
    return mapper.toDto(saved);
  }

  // ---------------- helpers ----------------

  private static void applyFreezerExtension(InventoryItem item, FreezerExtensionDto ext) {
    if (ext == null) {
      item.setFrozenAt(null);
      item.setMaxFreezeWeeks(null);
      item.setDefrostMethod(null);
      item.setDefrostLeadTimeHours(null);
      item.setSourceRecipeId(null);
      return;
    }
    item.setFrozenAt(ext.frozenAt());
    item.setMaxFreezeWeeks(ext.maxFreezeWeeks());
    item.setDefrostMethod(ext.defrostMethod());
    item.setDefrostLeadTimeHours(ext.defrostLeadTimeHours());
    item.setSourceRecipeId(ext.sourceRecipeId());
  }

  private void writeAuditRows(
      UUID inventoryItemId,
      UUID actorUserId,
      Set<String> changedFields,
      Snapshot before,
      Snapshot after,
      Instant now) {
    for (String field : changedFields) {
      JsonNode previous = before.toJson(field, objectMapper);
      JsonNode next = after.toJson(field, objectMapper);
      auditLogRepository.save(
          new InventoryAuditLog(
              UUID.randomUUID(),
              inventoryItemId,
              actorUserId,
              AuditActor.USER,
              actorUserId,
              field,
              previous,
              next,
              now));
    }
  }

  private JsonNode toSnapshotJson(InventoryItem item) {
    return objectMapper.valueToTree(Snapshot.of(item));
  }

  private static UUID currentTraceId() {
    String fromMdc = MDC.get(MDC_TRACE_ID);
    if (fromMdc != null && !fromMdc.isBlank()) {
      try {
        return UUID.fromString(fromMdc);
      } catch (IllegalArgumentException ignored) {
        // MDC value isn't a UUID — fall through to randomUUID.
      }
    }
    return UUID.randomUUID();
  }

  /**
   * Snapshot of the diff-relevant scalar + freezer-extension state of an inventory item. Used to
   * compute changed fields between pre-update and post-update states and to emit the JSONB payload
   * for the audit row.
   */
  record Snapshot(
      String name,
      String category,
      StorageLocation storageLocation,
      TrackingMode trackingMode,
      BigDecimal quantity,
      String unit,
      BigDecimal costPaid,
      StapleStatus status,
      boolean isStaple,
      LocalDate expiryDate,
      String ingredientMappingKey,
      String notes,
      ItemSource source,
      String sourceRef,
      ItemLifecycleStatus itemStatus,
      LocalDate frozenAt,
      Integer maxFreezeWeeks,
      DefrostMethod defrostMethod,
      Integer defrostLeadTimeHours,
      UUID sourceRecipeId) {

    static Snapshot of(InventoryItem entity) {
      return new Snapshot(
          entity.getName(),
          entity.getCategory(),
          entity.getStorageLocation(),
          entity.getTrackingMode(),
          entity.getQuantity(),
          entity.getUnit(),
          entity.getCostPaid(),
          entity.getStatus(),
          entity.isStaple(),
          entity.getExpiryDate(),
          entity.getIngredientMappingKey(),
          entity.getNotes(),
          entity.getSource(),
          entity.getSourceRef(),
          entity.getItemStatus(),
          entity.getFrozenAt(),
          entity.getMaxFreezeWeeks(),
          entity.getDefrostMethod(),
          entity.getDefrostLeadTimeHours(),
          entity.getSourceRecipeId());
    }

    /** Ordered set of field names that changed between this snapshot and {@code other}. */
    Set<String> diff(Snapshot other) {
      Set<String> changed = new LinkedHashSet<>();
      if (!Objects.equals(name, other.name)) changed.add(FIELD_NAME);
      if (!Objects.equals(category, other.category)) changed.add(FIELD_CATEGORY);
      if (!Objects.equals(storageLocation, other.storageLocation)) {
        changed.add(FIELD_STORAGE_LOCATION);
      }
      if (!Objects.equals(trackingMode, other.trackingMode)) changed.add(FIELD_TRACKING_MODE);
      if (compareQuantity(quantity, other.quantity)) changed.add(FIELD_QUANTITY);
      if (!Objects.equals(unit, other.unit)) changed.add(FIELD_UNIT);
      if (compareQuantity(costPaid, other.costPaid)) changed.add(FIELD_COST_PAID);
      if (!Objects.equals(status, other.status)) changed.add(FIELD_STATUS);
      if (isStaple != other.isStaple) changed.add(FIELD_IS_STAPLE);
      if (!Objects.equals(expiryDate, other.expiryDate)) changed.add(FIELD_EXPIRY_DATE);
      if (!Objects.equals(ingredientMappingKey, other.ingredientMappingKey)) {
        changed.add(FIELD_INGREDIENT_MAPPING_KEY);
      }
      if (!Objects.equals(notes, other.notes)) changed.add(FIELD_NOTES);
      if (!Objects.equals(source, other.source)) changed.add(FIELD_SOURCE);
      if (!Objects.equals(sourceRef, other.sourceRef)) changed.add(FIELD_SOURCE_REF);
      if (!Objects.equals(itemStatus, other.itemStatus)) changed.add(FIELD_ITEM_STATUS);
      if (freezerExtensionChanged(other)) changed.add(FIELD_FREEZER_EXTENSION);
      return changed;
    }

    private boolean freezerExtensionChanged(Snapshot other) {
      return !Objects.equals(frozenAt, other.frozenAt)
          || !Objects.equals(maxFreezeWeeks, other.maxFreezeWeeks)
          || !Objects.equals(defrostMethod, other.defrostMethod)
          || !Objects.equals(defrostLeadTimeHours, other.defrostLeadTimeHours)
          || !Objects.equals(sourceRecipeId, other.sourceRecipeId);
    }

    private static boolean compareQuantity(BigDecimal a, BigDecimal b) {
      if (a == null && b == null) return false;
      if (a == null || b == null) return true;
      return a.compareTo(b) != 0;
    }

    /** Project this snapshot's value for a given field into a JsonNode for the audit row. */
    JsonNode toJson(String field, ObjectMapper objectMapper) {
      Object value =
          switch (field) {
            case FIELD_NAME -> name;
            case FIELD_CATEGORY -> category;
            case FIELD_STORAGE_LOCATION -> storageLocation;
            case FIELD_TRACKING_MODE -> trackingMode;
            case FIELD_QUANTITY -> quantity;
            case FIELD_UNIT -> unit;
            case FIELD_COST_PAID -> costPaid;
            case FIELD_STATUS -> status;
            case FIELD_IS_STAPLE -> isStaple;
            case FIELD_EXPIRY_DATE -> expiryDate;
            case FIELD_INGREDIENT_MAPPING_KEY -> ingredientMappingKey;
            case FIELD_NOTES -> notes;
            case FIELD_SOURCE -> source;
            case FIELD_SOURCE_REF -> sourceRef;
            case FIELD_ITEM_STATUS -> itemStatus;
            case FIELD_FREEZER_EXTENSION ->
                new FreezerExtensionDto(
                    frozenAt, maxFreezeWeeks, defrostMethod, defrostLeadTimeHours, sourceRecipeId);
            default -> throw new IllegalStateException("Unknown field: " + field);
          };
      return objectMapper.valueToTree(value);
    }
  }
}
