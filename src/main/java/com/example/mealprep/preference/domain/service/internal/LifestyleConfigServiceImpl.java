package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.LifestyleConfigAuditEntryDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.api.mapper.LifestyleConfigMapper;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.entity.LifestyleConfig;
import com.example.mealprep.preference.domain.entity.LifestyleConfigAuditLog;
import com.example.mealprep.preference.domain.repository.LifestyleConfigAuditLogRepository;
import com.example.mealprep.preference.domain.repository.LifestyleConfigRepository;
import com.example.mealprep.preference.domain.service.LifestyleConfigQueryService;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.event.LifestyleConfigChangedEvent;
import com.example.mealprep.preference.event.LifestyleConfigInitialisedEvent;
import com.example.mealprep.preference.exception.LifestyleConfigNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of {@link LifestyleConfigQueryService} and {@link
 * LifestyleConfigUpdateService}. Mirrors the {@code PreferenceServiceImpl} pattern for 01a hard
 * constraints — reads run {@code readOnly = true}; writes run REQUIRED (top-level transactions).
 *
 * <p><b>Diff topology.</b> {@code update} writes one audit row per top-level section that changed.
 * Top-level sections are compared structurally via Jackson tree equality (so reorder-only changes
 * in inner Maps/Lists do not flag a section as changed). No-op sections produce no rows; a no-op
 * PUT writes no rows but still bumps {@code @Version} on flush — accepted, mirrors 01a behaviour.
 *
 * <p><b>Initialise topology.</b> {@code initialise} writes a single audit row at {@code fieldPath =
 * "*"} carrying the whole inbound document as {@code newValueJson} and {@code NullNode} as {@code
 * previousValueJson}. Picked per ticket §275 "or one summary audit row" suggestion — simpler than
 * per-section rows, and the audit-log section filter naturally ignores the {@code "*"} row.
 */
@Service
public class LifestyleConfigServiceImpl
    implements LifestyleConfigQueryService, LifestyleConfigUpdateService {

  private static final Logger log = LoggerFactory.getLogger(LifestyleConfigServiceImpl.class);

  /**
   * Sentinel field-path used for the single audit row emitted by {@link #initialise}. Not in the
   * set of section names — clients filtering {@code ?section=batchCooking} will not see this row.
   */
  static final String FIELD_PATH_INIT_SUMMARY = "*";

  /** Section names exposed in {@link LifestyleConfigChangedEvent#changedSections}. */
  static final String SECTION_MEAL_STRUCTURE = "mealStructure";

  static final String SECTION_MEAL_TIMING = "mealTiming";
  static final String SECTION_NOVELTY_TOLERANCE = "noveltyTolerance";
  static final String SECTION_COOKING_CONTEXTS = "cookingContexts";
  static final String SECTION_BATCH_COOKING = "batchCooking";
  static final String SECTION_REHEATING_PREFERENCES = "reheatingPreferences";
  static final String SECTION_EATING_CONTEXT = "eatingContext";
  static final String SECTION_SEASONAL_PREFERENCES = "seasonalPreferences";
  static final String SECTION_MEAL_TYPE_PREFERENCES = "mealTypePreferences";
  static final String SECTION_ACCOMPANIMENTS = "accompaniments";
  static final String SECTION_GROCERY_QUALITY_PREFERENCES = "groceryQualityPreferences";
  static final String SECTION_PANTRY_TRACKING = "pantryTracking";

  private final LifestyleConfigRepository repository;
  private final LifestyleConfigAuditLogRepository auditRepository;
  private final LifestyleConfigMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public LifestyleConfigServiceImpl(
      LifestyleConfigRepository repository,
      LifestyleConfigAuditLogRepository auditRepository,
      LifestyleConfigMapper mapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.auditRepository = auditRepository;
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<LifestyleConfigDto> getLifestyleConfig(UUID userId) {
    return repository.findByUserId(userId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<LifestyleConfigDto> getLifestyleConfigsByUserIds(List<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Collections.emptyList();
    }
    return mapper.toDtos(repository.findByUserIdIn(userIds));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<LifestyleConfigAuditEntryDto> getAuditLog(UUID userId, Pageable pageable) {
    Optional<LifestyleConfig> agg = repository.findByUserId(userId);
    if (agg.isEmpty()) {
      return Page.empty(pageable);
    }
    return auditRepository
        .findByLifestyleConfigIdOrderByOccurredAtDesc(agg.get().getId(), pageable)
        .map(mapper::toAuditEntryDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<LifestyleConfigAuditEntryDto> getAuditLogForSection(
      UUID userId, String sectionPath, Pageable pageable) {
    Optional<LifestyleConfig> agg = repository.findByUserId(userId);
    if (agg.isEmpty()) {
      return Page.empty(pageable);
    }
    return auditRepository
        .findByLifestyleConfigIdAndFieldPathStartingWithOrderByOccurredAtDesc(
            agg.get().getId(), sectionPath, pageable)
        .map(mapper::toAuditEntryDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public LifestyleConfigDto initialise(UUID userId, UpdateLifestyleConfigRequest request) {
    Optional<LifestyleConfig> existing = repository.findByUserId(userId);
    if (existing.isPresent()) {
      // Idempotent — onboarding may be retried; return the existing aggregate unchanged.
      return mapper.toDto(existing.get());
    }
    Instant now = Instant.now(clock);
    LifestyleConfig aggregate =
        LifestyleConfig.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .document(request.document())
            .lastReviewPromptAt(null)
            .build();
    LifestyleConfig saved = repository.save(aggregate);

    // Single summary audit row at fieldPath = "*". Per ticket §275 — picked the "or one summary
    // audit row" option for simplicity; the section filter on the audit-log endpoint naturally
    // ignores this row (it never matches "<section>" or starts-with "<section>.").
    JsonNode newJson = objectMapper.valueToTree(request.document());
    auditRepository.save(
        LifestyleConfigAuditLog.builder()
            .id(UUID.randomUUID())
            .lifestyleConfig(saved)
            .actorUserId(userId)
            .fieldPath(FIELD_PATH_INIT_SUMMARY)
            .previousValueJson(NullNode.getInstance())
            .newValueJson(newJson == null ? NullNode.getInstance() : newJson)
            .occurredAt(now)
            .build());

    eventPublisher.publishEvent(
        new LifestyleConfigInitialisedEvent(userId, saved.getId(), UUID.randomUUID(), now));
    log.info("lifestyle config initialised userId={} lifestyleConfigId={}", userId, saved.getId());
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public LifestyleConfigDto update(
      UUID userId, UpdateLifestyleConfigRequest request, UUID actorUserId) {
    LifestyleConfig aggregate =
        repository
            .findByUserId(userId)
            .orElseThrow(() -> new LifestyleConfigNotFoundException(userId));

    // Pre-check the version so a stale expectedVersion surfaces immediately as 409 — without this
    // a no-op PUT would silently accept the stale version (no dirty fields → no Hibernate flush).
    if (aggregate.getOptimisticVersion() != request.expectedVersion()) {
      throw new ObjectOptimisticLockingFailureException(LifestyleConfig.class, aggregate.getId());
    }

    LifestyleConfigDocument before = aggregate.getDocument();
    LifestyleConfigDocument after = request.document();

    Set<String> changedSections = computeChangedSections(before, after);

    if (changedSections.isEmpty()) {
      // No-op PUT: no audit rows, no event. @Version stays put (Hibernate flushes only when the
      // entity is dirty; document reference assignment with structural equality is a no-op here).
      log.info(
          "lifestyle config PUT was a no-op userId={} version={}",
          userId,
          aggregate.getOptimisticVersion());
      return mapper.toDto(aggregate);
    }

    Instant now = Instant.now(clock);
    aggregate.setDocument(after);
    LifestyleConfig saved = repository.saveAndFlush(aggregate);

    List<LifestyleConfigAuditLog> rows =
        buildAuditRows(saved, actorUserId, before, after, changedSections, now);
    auditRepository.saveAll(rows);

    eventPublisher.publishEvent(
        new LifestyleConfigChangedEvent(
            userId,
            saved.getId(),
            Collections.unmodifiableSet(new LinkedHashSet<>(changedSections)),
            UUID.randomUUID(),
            now));

    log.info(
        "lifestyle config updated userId={} lifestyleConfigId={} changedSections={} version={}",
        userId,
        saved.getId(),
        changedSections,
        saved.getOptimisticVersion());
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public LifestyleConfigDto markReviewed(UUID userId) {
    LifestyleConfig aggregate =
        repository
            .findByUserId(userId)
            .orElseThrow(() -> new LifestyleConfigNotFoundException(userId));
    aggregate.setLastReviewPromptAt(null);
    // saveAndFlush so the @Version bump materialises before we map to DTO.
    LifestyleConfig saved = repository.saveAndFlush(aggregate);
    log.info(
        "lifestyle config marked reviewed userId={} lifestyleConfigId={} version={}",
        userId,
        saved.getId(),
        saved.getOptimisticVersion());
    return mapper.toDto(saved);
  }

  // ---------------- Diff ----------------

  /**
   * Insertion-ordered set of top-level section names whose JSON projection differs between {@code
   * before} and {@code after}. {@code null} on either side is treated as a section absence; if both
   * are null the section is unchanged.
   */
  private Set<String> computeChangedSections(
      LifestyleConfigDocument before, LifestyleConfigDocument after) {
    Set<String> changed = new LinkedHashSet<>();
    if (before == null && after == null) {
      return changed;
    }
    if (before == null || after == null) {
      // Whole document appeared or disappeared — flag every populated section on the non-null side.
      LifestyleConfigDocument populated = before == null ? after : before;
      addIfNotNull(changed, SECTION_MEAL_STRUCTURE, populated.mealStructure());
      addIfNotNull(changed, SECTION_MEAL_TIMING, populated.mealTiming());
      addIfNotNull(changed, SECTION_NOVELTY_TOLERANCE, populated.noveltyTolerance());
      addIfNotNull(changed, SECTION_COOKING_CONTEXTS, populated.cookingContexts());
      addIfNotNull(changed, SECTION_BATCH_COOKING, populated.batchCooking());
      addIfNotNull(changed, SECTION_REHEATING_PREFERENCES, populated.reheatingPreferences());
      addIfNotNull(changed, SECTION_EATING_CONTEXT, populated.eatingContext());
      addIfNotNull(changed, SECTION_SEASONAL_PREFERENCES, populated.seasonalPreferences());
      addIfNotNull(changed, SECTION_MEAL_TYPE_PREFERENCES, populated.mealTypePreferences());
      addIfNotNull(changed, SECTION_ACCOMPANIMENTS, populated.accompaniments());
      addIfNotNull(
          changed, SECTION_GROCERY_QUALITY_PREFERENCES, populated.groceryQualityPreferences());
      addIfNotNull(changed, SECTION_PANTRY_TRACKING, populated.pantryTracking());
      return changed;
    }
    addIfDifferent(changed, SECTION_MEAL_STRUCTURE, before.mealStructure(), after.mealStructure());
    addIfDifferent(changed, SECTION_MEAL_TIMING, before.mealTiming(), after.mealTiming());
    addIfDifferent(
        changed, SECTION_NOVELTY_TOLERANCE, before.noveltyTolerance(), after.noveltyTolerance());
    addIfDifferent(
        changed, SECTION_COOKING_CONTEXTS, before.cookingContexts(), after.cookingContexts());
    addIfDifferent(changed, SECTION_BATCH_COOKING, before.batchCooking(), after.batchCooking());
    addIfDifferent(
        changed,
        SECTION_REHEATING_PREFERENCES,
        before.reheatingPreferences(),
        after.reheatingPreferences());
    addIfDifferent(changed, SECTION_EATING_CONTEXT, before.eatingContext(), after.eatingContext());
    addIfDifferent(
        changed,
        SECTION_SEASONAL_PREFERENCES,
        before.seasonalPreferences(),
        after.seasonalPreferences());
    addIfDifferent(
        changed,
        SECTION_MEAL_TYPE_PREFERENCES,
        before.mealTypePreferences(),
        after.mealTypePreferences());
    addIfDifferent(
        changed, SECTION_ACCOMPANIMENTS, before.accompaniments(), after.accompaniments());
    addIfDifferent(
        changed,
        SECTION_GROCERY_QUALITY_PREFERENCES,
        before.groceryQualityPreferences(),
        after.groceryQualityPreferences());
    addIfDifferent(
        changed, SECTION_PANTRY_TRACKING, before.pantryTracking(), after.pantryTracking());
    return changed;
  }

  private void addIfNotNull(Set<String> changed, String name, Object value) {
    if (value != null) {
      changed.add(name);
    }
  }

  /**
   * Compare two section values via Jackson tree equality. Java {@code Object.equals} on records
   * already handles structural equality for record fields, but tree equality is safer for the
   * {@code Map<String, X>} children (insertion-order independence) and matches the audit-log row
   * shape.
   */
  private void addIfDifferent(Set<String> changed, String name, Object before, Object after) {
    JsonNode b = toNode(before);
    JsonNode a = toNode(after);
    if (!b.equals(a)) {
      changed.add(name);
    }
  }

  private JsonNode toNode(Object value) {
    if (value == null) {
      return NullNode.getInstance();
    }
    return objectMapper.valueToTree(value);
  }

  private List<LifestyleConfigAuditLog> buildAuditRows(
      LifestyleConfig saved,
      UUID actorUserId,
      LifestyleConfigDocument before,
      LifestyleConfigDocument after,
      Set<String> changedSections,
      Instant occurredAt) {
    List<LifestyleConfigAuditLog> rows = new ArrayList<>(changedSections.size());
    for (String section : changedSections) {
      JsonNode prev = toNode(sectionValue(before, section));
      JsonNode next = toNode(sectionValue(after, section));
      rows.add(
          LifestyleConfigAuditLog.builder()
              .id(UUID.randomUUID())
              .lifestyleConfig(saved)
              .actorUserId(actorUserId)
              .fieldPath(section)
              .previousValueJson(prev)
              .newValueJson(next)
              .occurredAt(occurredAt)
              .build());
    }
    return rows;
  }

  private static Object sectionValue(LifestyleConfigDocument doc, String section) {
    if (doc == null) {
      return null;
    }
    return switch (section) {
      case SECTION_MEAL_STRUCTURE -> doc.mealStructure();
      case SECTION_MEAL_TIMING -> doc.mealTiming();
      case SECTION_NOVELTY_TOLERANCE -> doc.noveltyTolerance();
      case SECTION_COOKING_CONTEXTS -> doc.cookingContexts();
      case SECTION_BATCH_COOKING -> doc.batchCooking();
      case SECTION_REHEATING_PREFERENCES -> doc.reheatingPreferences();
      case SECTION_EATING_CONTEXT -> doc.eatingContext();
      case SECTION_SEASONAL_PREFERENCES -> doc.seasonalPreferences();
      case SECTION_MEAL_TYPE_PREFERENCES -> doc.mealTypePreferences();
      case SECTION_ACCOMPANIMENTS -> doc.accompaniments();
      case SECTION_GROCERY_QUALITY_PREFERENCES -> doc.groceryQualityPreferences();
      case SECTION_PANTRY_TRACKING -> doc.pantryTracking();
      default -> throw new IllegalStateException("Unknown section: " + section);
    };
  }
}
