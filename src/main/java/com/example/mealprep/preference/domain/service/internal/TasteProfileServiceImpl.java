package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.TasteProfileAuditEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.TasteProfileVersionDto;
import com.example.mealprep.preference.api.dto.TriggerTasteProfileRefreshRequest;
import com.example.mealprep.preference.api.dto.UpdateTasteProfileRequest;
import com.example.mealprep.preference.api.mapper.TasteProfileMapper;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.entity.ActorType;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.entity.TasteProfileAuditLog;
import com.example.mealprep.preference.domain.entity.TasteProfileChangeType;
import com.example.mealprep.preference.domain.entity.TasteProfileTrigger;
import com.example.mealprep.preference.domain.entity.TasteProfileVersion;
import com.example.mealprep.preference.domain.entity.TasteVectorStatus;
import com.example.mealprep.preference.domain.repository.TasteProfileAuditLogRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import com.example.mealprep.preference.domain.repository.TasteProfileVersionRepository;
import com.example.mealprep.preference.domain.service.TasteProfileQueryService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import com.example.mealprep.preference.event.TasteProfileChangedEvent;
import com.example.mealprep.preference.event.TasteProfileRefreshRequestedEvent;
import com.example.mealprep.preference.exception.TasteProfileNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 * Single implementation of both {@link TasteProfileQueryService} and {@link
 * TasteProfileUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED. Every successful write produces
 * three side-effects inside the same transaction:
 *
 * <ol>
 *   <li>monotonic increment of {@code documentVersion} (no decrements ever — rollback creates a new
 *       version with {@code change_type = ROLLED_BACK}, not a version decrement);
 *   <li>one row in {@code preference_taste_profile_audit};
 *   <li>one row in {@code preference_taste_profile_versions};
 * </ol>
 *
 * <p>Followed by exactly one {@code TasteProfileChangedEvent} published AFTER the transaction
 * commits — Spring routes it via the default publisher; downstream
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} consumers see at-most-one delivery per
 * write.
 */
@Service
public class TasteProfileServiceImpl
    implements TasteProfileQueryService, TasteProfileUpdateService {

  private static final Logger log = LoggerFactory.getLogger(TasteProfileServiceImpl.class);

  private final TasteProfileRepository tasteProfileRepository;
  private final TasteProfileVersionRepository versionRepository;
  private final TasteProfileAuditLogRepository auditLogRepository;
  private final TasteProfileMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final TasteProfileDeltaApplier deltaApplier;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public TasteProfileServiceImpl(
      TasteProfileRepository tasteProfileRepository,
      TasteProfileVersionRepository versionRepository,
      TasteProfileAuditLogRepository auditLogRepository,
      TasteProfileMapper mapper,
      ApplicationEventPublisher eventPublisher,
      TasteProfileDeltaApplier deltaApplier,
      ObjectMapper objectMapper,
      Clock clock) {
    this.tasteProfileRepository = tasteProfileRepository;
    this.versionRepository = versionRepository;
    this.auditLogRepository = auditLogRepository;
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.deltaApplier = deltaApplier;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<TasteProfileDto> getTasteProfile(UUID userId) {
    return tasteProfileRepository.findByUserId(userId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public List<TasteProfileDto> getTasteProfilesByUserIds(List<UUID> userIds) {
    if (userIds == null || userIds.isEmpty()) {
      return Collections.emptyList();
    }
    return mapper.toDtos(tasteProfileRepository.findByUserIdIn(userIds));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<TasteProfileVersionDto> getVersions(UUID userId, Pageable pageable) {
    Optional<TasteProfile> profile = tasteProfileRepository.findByUserId(userId);
    if (profile.isEmpty()) {
      return Page.empty(pageable);
    }
    return versionRepository
        .findByTasteProfileIdOrderByDocumentVersionDesc(profile.get().getId(), pageable)
        .map(mapper::toVersionDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TasteProfileVersionDto> getVersion(UUID userId, int documentVersion) {
    return tasteProfileRepository
        .findByUserId(userId)
        .flatMap(
            profile ->
                versionRepository
                    .findByTasteProfileIdAndDocumentVersion(profile.getId(), documentVersion)
                    .map(mapper::toVersionDto));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<TasteProfileAuditEntryDto> getAuditLog(UUID userId, Pageable pageable) {
    Optional<TasteProfile> profile = tasteProfileRepository.findByUserId(userId);
    if (profile.isEmpty()) {
      return Page.empty(pageable);
    }
    return auditLogRepository
        .findByTasteProfileIdOrderByOccurredAtDesc(profile.get().getId(), pageable)
        .map(mapper::toAuditEntryDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public TasteProfileDto initialise(UUID userId) {
    Optional<TasteProfile> existing = tasteProfileRepository.findByUserId(userId);
    if (existing.isPresent()) {
      // Idempotent: avoids races at user-creation where two paths might both try to seed.
      log.info("taste profile already initialised userId={} returning existing", userId);
      return mapper.toDto(existing.get());
    }

    LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    TasteProfileDocument document = TasteProfileDocument.empty(today);

    TasteProfile profile =
        TasteProfile.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .document(document)
            .documentVersion(1)
            .feedbackCursor(null)
            .basedOnFeedbackCount(0)
            .lastDeltaAppliedAt(null)
            .lastTokenEstimate(null)
            .tasteVectorStatus(TasteVectorStatus.PENDING)
            .tasteVectorDocVersion(null)
            .tasteVectorModelId(null)
            .tasteVectorEmbeddedAt(null)
            .build();
    TasteProfile saved = tasteProfileRepository.save(profile);

    Instant now = Instant.now(clock);
    writeVersionSnapshot(saved, document, TasteProfileTrigger.MANUAL, null, null, null, now);
    writeAudit(
        saved,
        userId,
        ActorType.USER,
        TasteProfileChangeType.INITIALIZED,
        null,
        1,
        "taste profile initialised",
        null,
        now);

    eventPublisher.publishEvent(
        new TasteProfileChangedEvent(
            userId,
            saved.getId(),
            saved.getDocumentVersion(),
            TasteProfileChangeType.INITIALIZED,
            ActorType.USER,
            null,
            now));

    log.info(
        "taste profile initialised userId={} tasteProfileId={} documentVersion={}",
        userId,
        saved.getId(),
        saved.getDocumentVersion());
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public TasteProfileDto applyManualOverride(
      UUID userId, UpdateTasteProfileRequest request, UUID actorUserId) {
    TasteProfile profile =
        tasteProfileRepository
            .findByUserId(userId)
            .orElseThrow(() -> new TasteProfileNotFoundException(userId));

    // Optimistic-lock pre-check: surface the 409 immediately rather than waiting for Hibernate's
    // flush-time bump (which won't fire if the payload exactly equals the stored document).
    if (profile.getOptimisticVersion() != request.expectedVersion()) {
      throw new ObjectOptimisticLockingFailureException(TasteProfile.class, profile.getId());
    }

    int previousVersion = profile.getDocumentVersion();
    int newVersion = previousVersion + 1;
    Instant now = Instant.now(clock);
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

    // Stamp the document's internal version + lastUpdated to match the entity. The HLD requires
    // both fields to
    // bump in lock-step on every successful write — clients reading the document JSONB and the
    // entity's
    // documentVersion must see the same integer.
    TasteProfileDocument inboundDocument = request.document();
    TasteProfileDocument stampedDocument =
        new TasteProfileDocument(
            today,
            newVersion,
            inboundDocument.basedOnFeedbackCount(),
            inboundDocument.feedbackCursor(),
            inboundDocument.softConstraints(),
            inboundDocument.flavourPreferences(),
            inboundDocument.texturePreferences(),
            inboundDocument.ingredientPreferences(),
            inboundDocument.cuisinePreferences(),
            inboundDocument.cookingPreferences(),
            inboundDocument.portionStyle(),
            inboundDocument.householdContext(),
            inboundDocument.recipesToRepeat(),
            inboundDocument.recipesToAvoid(),
            inboundDocument.activeExperiments(),
            inboundDocument.learnedInsights());

    profile.setDocument(stampedDocument);
    profile.setDocumentVersion(newVersion);
    // Manual overrides invalidate the embedded vector — flag for re-embed.
    profile.setTasteVectorStatus(TasteVectorStatus.PENDING);
    // saveAndFlush so the @Version bump materialises before we map to DTO (the controller assert on
    // the version
    // in the response body needs the flushed value).
    TasteProfile saved = tasteProfileRepository.saveAndFlush(profile);

    writeVersionSnapshot(saved, stampedDocument, TasteProfileTrigger.MANUAL, null, null, null, now);
    writeAudit(
        saved,
        actorUserId,
        ActorType.USER,
        TasteProfileChangeType.MANUAL_OVERRIDE,
        previousVersion,
        newVersion,
        "manual override",
        null,
        now);

    eventPublisher.publishEvent(
        new TasteProfileChangedEvent(
            userId,
            saved.getId(),
            newVersion,
            TasteProfileChangeType.MANUAL_OVERRIDE,
            ActorType.USER,
            null,
            now));

    log.info(
        "taste profile manual override userId={} previousVersion={} newVersion={}",
        userId,
        previousVersion,
        newVersion);
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public TasteProfileDto applyDeltas(UUID userId, ApplyTasteProfileDeltasRequest request) {
    TasteProfile profile =
        tasteProfileRepository
            .findByUserId(userId)
            .orElseThrow(() -> new TasteProfileNotFoundException(userId));
    // The applier is a stub in 01c and always throws — feedback bridge in 01g must not call this
    // until the
    // deferred 01c-delta-applier ticket lands. The lookup-then-throw shape ensures any caller that
    // wrongly
    // invokes this still sees the canonical "deferred" message and not a null pointer.
    deltaApplier.apply(profile.getDocument(), request);
    // Unreachable in 01c. Kept here so the compiler is satisfied and the IDE doesn't flag unused
    // locals.
    return mapper.toDto(profile);
  }

  @Override
  @Transactional
  public TasteProfileDto triggerRefresh(
      UUID userId, TriggerTasteProfileRefreshRequest request, UUID actorUserId, UUID traceId) {
    TasteProfile profile =
        tasteProfileRepository
            .findByUserId(userId)
            .orElseThrow(() -> new TasteProfileNotFoundException(userId));

    Instant now = Instant.now(clock);
    writeAudit(
        profile,
        actorUserId,
        ActorType.USER,
        TasteProfileChangeType.REFRESH_TRIGGERED,
        profile.getDocumentVersion(),
        profile.getDocumentVersion(),
        "refresh requested",
        traceId,
        now);

    eventPublisher.publishEvent(
        new TasteProfileRefreshRequestedEvent(
            userId,
            profile.getId(),
            request != null ? request.feedbackRangeStart() : null,
            request != null ? request.feedbackRangeEnd() : null,
            traceId,
            now));
    // We do NOT publish TasteProfileChangedEvent here — no document mutation happened. The event
    // would mislead the
    // downstream embedding listener into a no-op recompute.

    log.info(
        "taste profile refresh requested userId={} tasteProfileId={} traceId={}",
        userId,
        profile.getId(),
        traceId);
    return mapper.toDto(profile);
  }

  // ---------------- helpers ----------------

  private void writeVersionSnapshot(
      TasteProfile profile,
      TasteProfileDocument snapshot,
      TasteProfileTrigger trigger,
      String feedbackRangeStart,
      String feedbackRangeEnd,
      String modelTierUsed,
      Instant generatedAt) {
    JsonNode emptyDeltas = JsonNodeFactory.instance.arrayNode();
    versionRepository.save(
        TasteProfileVersion.builder()
            .id(UUID.randomUUID())
            .tasteProfile(profile)
            .documentVersion(profile.getDocumentVersion())
            .documentSnapshot(snapshot)
            .feedbackRangeStart(feedbackRangeStart)
            .feedbackRangeEnd(feedbackRangeEnd)
            .trigger(trigger)
            .deltasApplied(emptyDeltas)
            .modelTierUsed(modelTierUsed == null ? "manual" : modelTierUsed)
            .generatedAt(generatedAt)
            .build());
  }

  private void writeAudit(
      TasteProfile profile,
      UUID actorUserId,
      ActorType actorType,
      TasteProfileChangeType changeType,
      Integer previousDocumentVersion,
      int newDocumentVersion,
      String summary,
      UUID traceId,
      Instant occurredAt) {
    auditLogRepository.save(
        TasteProfileAuditLog.builder()
            .id(UUID.randomUUID())
            .tasteProfile(profile)
            .actorUserId(actorUserId)
            .actorType(actorType)
            .changeType(changeType)
            .previousDocumentVersion(previousDocumentVersion)
            .newDocumentVersion(newDocumentVersion)
            .summary(summary)
            .traceId(traceId)
            .occurredAt(occurredAt)
            .build());
  }

  /**
   * Exposed so unit tests can verify the {@code ObjectMapper} dependency was wired through. The
   * delta-applier ticket will use the mapper to serialise the {@code deltasApplied} JSON column
   * when real (non-empty) delta batches land; until then the field exists but is otherwise unused.
   */
  ObjectMapper objectMapper() {
    return objectMapper;
  }
}
