package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.AgeRestrictionDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardConstraintsAuditEntryDto;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.api.mapper.HardConstraintsMapper;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.repository.HardIntoleranceRepository;
import com.example.mealprep.preference.domain.service.PreferenceQueryService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.example.mealprep.preference.exception.HardConstraintsNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of both {@link PreferenceQueryService} and {@link PreferenceUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED (top-level transactions). The
 * update path is field-level diffing — one audit row per genuinely changed field; if the request is
 * a no-op, no rows are written and no event is published.
 *
 * <p>{@code dietaryIdentityExceptions} are diffed as a set (allows + frequency + context) so
 * order-only changes are no-ops. The same applies to intolerances and age restrictions.
 */
@Service
public class PreferenceServiceImpl implements PreferenceQueryService, PreferenceUpdateService {

  private static final Logger log = LoggerFactory.getLogger(PreferenceServiceImpl.class);

  static final String FIELD_ALLERGIES = "allergies";
  static final String FIELD_DIETARY_IDENTITY_BASE = "dietaryIdentityBase";
  static final String FIELD_DIETARY_IDENTITY_LABEL = "dietaryIdentityLabel";
  static final String FIELD_DIETARY_IDENTITY_EXCEPTIONS = "dietaryIdentityExceptions";
  static final String FIELD_MEDICAL_DIETS = "medicalDiets";
  static final String FIELD_INTOLERANCES = "intolerances";
  static final String FIELD_AGE_RESTRICTIONS = "ageRestrictions";

  private static final String DEFAULT_DIETARY_BASE = "omnivore";

  private final HardConstraintsRepository hardConstraintsRepository;
  private final HardConstraintsAuditLogRepository auditLogRepository;
  private final HardIntoleranceRepository hardIntoleranceRepository;
  private final HardConstraintsMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /**
   * Field-injected so the existing constructor (and {@code HardConstraintsServiceImplTest}, which
   * mocks the repository layer and never flushes) stay unchanged. Mirrors the
   * {@code @PersistenceContext EntityManager} idiom already used in {@code AdvisoryLockServiceImpl}
   * / {@code DiscoveryServiceImpl}. Used to force-increment the aggregate root's {@code @Version}
   * when only an owned child collection (intolerances, exceptions, age restrictions) changed —
   * Hibernate does not dirty the parent root on a child-collection-only mutation, so the
   * {@code @Version} would otherwise stay put (the optimistic-locking contract is "any aggregate
   * mutation bumps {@code @Version}"). Null in the pure unit test, where there is no real flush to
   * protect.
   */
  @PersistenceContext private EntityManager entityManager;

  public PreferenceServiceImpl(
      HardConstraintsRepository hardConstraintsRepository,
      HardConstraintsAuditLogRepository auditLogRepository,
      HardIntoleranceRepository hardIntoleranceRepository,
      HardConstraintsMapper mapper,
      ApplicationEventPublisher eventPublisher,
      ObjectMapper objectMapper,
      Clock clock) {
    this.hardConstraintsRepository = hardConstraintsRepository;
    this.auditLogRepository = auditLogRepository;
    this.hardIntoleranceRepository = hardIntoleranceRepository;
    this.mapper = mapper;
    this.eventPublisher = eventPublisher;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<HardConstraintsDto> getHardConstraints(UUID userId) {
    return hardConstraintsRepository.findWithChildrenByUserId(userId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<HardConstraintsAuditEntryDto> getHardConstraintsAuditLog(
      UUID userId, Pageable pageable) {
    Optional<HardConstraints> aggregate = hardConstraintsRepository.findByUserId(userId);
    if (aggregate.isEmpty()) {
      return Page.empty(pageable);
    }
    return auditLogRepository
        .findByHardConstraintsIdOrderByOccurredAtDesc(aggregate.get().getId(), pageable)
        .map(mapper::toAuditEntryDto);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public HardConstraintsDto initialiseHardConstraints(UUID userId) {
    Optional<HardConstraints> existing = hardConstraintsRepository.findByUserId(userId);
    if (existing.isPresent()) {
      // Idempotent — if already initialised, return the existing aggregate.
      return mapper.toDto(existing.get());
    }
    HardConstraints aggregate =
        HardConstraints.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .allergies(new ArrayList<>())
            .dietaryIdentityBase(DEFAULT_DIETARY_BASE)
            .dietaryIdentityLabel(null)
            .medicalDiets(new ArrayList<>())
            .exceptions(new ArrayList<>())
            .intolerances(new ArrayList<>())
            .ageRestrictions(new ArrayList<>())
            .build();
    HardConstraints saved = hardConstraintsRepository.save(aggregate);
    log.info("hard constraints initialised userId={} hardConstraintsId={}", userId, saved.getId());
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public HardConstraintsDto updateHardConstraints(
      UUID userId, UpdateHardConstraintsRequest request, UUID actorUserId) {
    HardConstraints aggregate =
        hardConstraintsRepository
            .findWithChildrenByUserId(userId)
            .orElseThrow(() -> new HardConstraintsNotFoundException(userId));

    // Optimistic-lock pre-check: surface the 409 immediately rather than waiting for Hibernate's
    // increment-on-flush, which only fires on actual writes (no-op PUTs would silently pass).
    if (aggregate.getVersion() != request.expectedVersion()) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          HardConstraints.class, aggregate.getId());
    }

    // Capture current state BEFORE mutation; the diff compares old vs new values.
    Snapshot before = Snapshot.of(aggregate);

    // Apply scalar fields.
    aggregate.setAllergies(new ArrayList<>(request.allergies()));
    aggregate.setMedicalDiets(new ArrayList<>(request.medicalDiets()));
    aggregate.setDietaryIdentityBase(request.dietaryIdentity().base());
    aggregate.setDietaryIdentityLabel(request.dietaryIdentity().labelForDisplay());

    // Replace children in place — cascade + orphanRemoval take care of delete + insert.
    aggregate.replaceExceptions(toExceptionEntities(request.dietaryIdentity().exceptions()));
    aggregate.replaceIntolerances(toIntoleranceEntities(request.intolerances()));
    aggregate.replaceAgeRestrictions(toAgeRestrictionEntities(request.ageRestrictions()));

    Snapshot after = Snapshot.of(aggregate);
    Set<String> changedFields = before.diff(after);

    if (changedFields.isEmpty()) {
      // No-op PUT: no audit row, no event, no version bump (version only increments on flush of
      // dirty fields; an unmodified entity stays at the same version).
      log.info(
          "hard constraints PUT was a no-op userId={} version={}", userId, aggregate.getVersion());
      return mapper.toDto(aggregate);
    }

    Instant now = Instant.now(clock);
    writeAuditRows(aggregate.getId(), actorUserId, changedFields, before, after, now);

    // Force-increment the root @Version. A change to only an owned child collection (e.g. adding an
    // intolerance) does NOT dirty the parent root in Hibernate, so without this the parent's UPDATE
    // (and its @Version bump) never fires — the optimistic-locking contract is "any aggregate
    // mutation bumps @Version", and the IT asserts the post-write version.
    // OPTIMISTIC_FORCE_INCREMENT
    // makes Hibernate issue the version UPDATE on the root regardless of which fields changed.
    forceIncrementRootVersion(aggregate);

    // saveAndFlush so the @Version bump materialises before we map to DTO — otherwise the
    // response would carry the stale version. The IT explicitly asserts the post-PUT version.
    HardConstraints saved = hardConstraintsRepository.saveAndFlush(aggregate);
    eventPublisher.publishEvent(
        new HardConstraintsUpdatedEvent(userId, changedFields, UUID.randomUUID(), now));
    log.info(
        "hard constraints updated userId={} fieldsChanged={} version={}",
        userId,
        changedFields,
        saved.getVersion());
    return mapper.toDto(saved);
  }

  // ---------------- Directive provenance (nutrition/01j) ----------------

  /**
   * Stamp {@code sourceDirectiveId} + {@code autoExpiresAt} on the intolerance row a temporary
   * directive just added, so the future auto-expiry sweep can find and reverse exactly that row.
   *
   * <p>Module-internal helper invoked by {@code PreferenceDirectiveApplyTarget} (same module, in
   * {@code preference.spi.internal}) AFTER its {@code updateHardConstraints} call has added the row
   * (riding that method's audit + version + event machinery). Runs in the same transaction (no
   * {@code @Transactional} of its own — joins the caller's). Matches by {@code (hardConstraintsId,
   * substance)} among rows not yet stamped, so a re-add doesn't restamp an already-attributed row.
   */
  public void stampTemporaryConstraint(
      UUID userId, String substance, UUID directiveId, Instant autoExpiresAt) {
    HardConstraints aggregate =
        hardConstraintsRepository
            .findByUserId(userId)
            .orElseThrow(() -> new HardConstraintsNotFoundException(userId));
    List<HardIntolerance> candidates =
        hardIntoleranceRepository.findByHardConstraintsIdAndSubstanceAndSourceDirectiveIdIsNull(
            aggregate.getId(), substance);
    for (HardIntolerance row : candidates) {
      row.setSourceDirectiveId(directiveId);
      row.setAutoExpiresAt(autoExpiresAt);
    }
    if (!candidates.isEmpty()) {
      hardIntoleranceRepository.saveAll(candidates);
      log.info(
          "stamped temporary directive provenance userId={} substance={} directiveId={} rows={}",
          userId,
          substance,
          directiveId,
          candidates.size());
    }
  }

  @Override
  @Transactional
  public void removeTemporaryConstraint(UUID userId, UUID directiveId) {
    List<HardIntolerance> sourced = hardIntoleranceRepository.findBySourceDirectiveId(directiveId);
    // Best-effort + idempotent: scope to this user's rows; a directive whose rows the user has
    // since edited away (or one already reversed) leaves nothing to do — no audit, no event, no
    // throw.
    Optional<HardConstraints> aggregateOpt = hardConstraintsRepository.findByUserId(userId);
    if (sourced.isEmpty() || aggregateOpt.isEmpty()) {
      log.info(
          "removeTemporaryConstraint no-op userId={} directiveId={} (no surviving directive-sourced"
              + " rows)",
          userId,
          directiveId);
      return;
    }
    HardConstraints aggregate = aggregateOpt.get();
    List<HardIntolerance> toRemove = new ArrayList<>();
    for (HardIntolerance row : sourced) {
      if (row.getHardConstraints() != null
          && aggregate.getId().equals(row.getHardConstraints().getId())) {
        toRemove.add(row);
      }
    }
    if (toRemove.isEmpty()) {
      log.info(
          "removeTemporaryConstraint no-op userId={} directiveId={} (rows belong to another user)",
          userId,
          directiveId);
      return;
    }

    // Audit the intolerances field as a whole (mirrors the updateHardConstraints diff granularity):
    // previous = the full intolerance set, new = that set minus the removed directive-sourced rows.
    List<HardIntoleranceDto> previous = intoleranceDtos(aggregate.getIntolerances());
    aggregate.getIntolerances().removeAll(toRemove);
    List<HardIntoleranceDto> next = intoleranceDtos(aggregate.getIntolerances());

    Instant now = Instant.now(clock);
    auditLogRepository.save(
        new HardConstraintsAuditLog(
            UUID.randomUUID(),
            aggregate.getId(),
            userId,
            FIELD_INTOLERANCES,
            objectMapper.valueToTree(previous),
            objectMapper.valueToTree(next),
            now));

    // Same child-only-mutation concern as updateHardConstraints: removing intolerance rows doesn't
    // dirty the parent root, so force the @Version bump the contract (and the IT) require.
    forceIncrementRootVersion(aggregate);

    HardConstraints saved = hardConstraintsRepository.saveAndFlush(aggregate);
    eventPublisher.publishEvent(
        new HardConstraintsUpdatedEvent(
            userId, Set.of(FIELD_INTOLERANCES), UUID.randomUUID(), now));
    log.info(
        "removeTemporaryConstraint reversed userId={} directiveId={} removed={} version={}",
        userId,
        directiveId,
        toRemove.size(),
        saved.getVersion());
  }

  /**
   * Force Hibernate to bump the aggregate root's {@code @Version} even when only an owned child
   * collection changed. {@code OPTIMISTIC_FORCE_INCREMENT} schedules a version UPDATE on the root
   * at flush time; without it a child-only mutation leaves the parent clean and the version stale.
   *
   * <p>Guarded for the pure unit test ({@code HardConstraintsServiceImplTest}) where the {@code
   * EntityManager} is not injected (repositories are mocked, nothing is really flushed, so there is
   * no real version to protect).
   */
  private void forceIncrementRootVersion(HardConstraints aggregate) {
    if (entityManager != null) {
      entityManager.lock(aggregate, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }
  }

  private static List<HardIntoleranceDto> intoleranceDtos(List<HardIntolerance> rows) {
    List<HardIntoleranceDto> dtos = new ArrayList<>();
    if (rows != null) {
      for (HardIntolerance i : rows) {
        dtos.add(new HardIntoleranceDto(i.getSubstance(), i.getSeverity(), i.getNotes()));
      }
    }
    return dtos;
  }

  // ---------------- Diff + audit ----------------

  private void writeAuditRows(
      UUID hardConstraintsId,
      UUID actorUserId,
      Set<String> changedFields,
      Snapshot before,
      Snapshot after,
      Instant now) {
    for (String field : changedFields) {
      JsonNode previous = before.toJson(field, objectMapper);
      JsonNode next = after.toJson(field, objectMapper);
      auditLogRepository.save(
          new HardConstraintsAuditLog(
              UUID.randomUUID(), hardConstraintsId, actorUserId, field, previous, next, now));
    }
  }

  // ---------------- Entity construction ----------------

  private static List<DietaryIdentityException> toExceptionEntities(
      List<DietaryIdentityExceptionDto> dtos) {
    if (dtos == null || dtos.isEmpty()) {
      return Collections.emptyList();
    }
    List<DietaryIdentityException> result = new ArrayList<>(dtos.size());
    for (DietaryIdentityExceptionDto dto : dtos) {
      result.add(
          DietaryIdentityException.builder()
              .id(UUID.randomUUID())
              .allows(dto.allows())
              .frequency(dto.frequency())
              .context(dto.context())
              .build());
    }
    return result;
  }

  private static List<HardIntolerance> toIntoleranceEntities(List<HardIntoleranceDto> dtos) {
    if (dtos == null || dtos.isEmpty()) {
      return Collections.emptyList();
    }
    List<HardIntolerance> result = new ArrayList<>(dtos.size());
    for (HardIntoleranceDto dto : dtos) {
      result.add(
          HardIntolerance.builder()
              .id(UUID.randomUUID())
              .substance(dto.substance())
              .severity(dto.severity())
              .notes(dto.notes())
              .build());
    }
    return result;
  }

  private static List<AgeRestriction> toAgeRestrictionEntities(List<AgeRestrictionDto> dtos) {
    if (dtos == null || dtos.isEmpty()) {
      return Collections.emptyList();
    }
    List<AgeRestriction> result = new ArrayList<>(dtos.size());
    for (AgeRestrictionDto dto : dtos) {
      result.add(
          AgeRestriction.builder()
              .id(UUID.randomUUID())
              .ruleKey(dto.ruleKey())
              .autoPopulated(dto.autoPopulated())
              .build());
    }
    return result;
  }

  /**
   * Snapshot of an aggregate's diff-relevant fields, used to compute changed fields between the
   * pre-update and post-update states. Children are projected into immutable DTO lists ordered by
   * the natural key (so reorder-only changes are no-ops).
   */
  private record Snapshot(
      List<String> allergies,
      String dietaryIdentityBase,
      String dietaryIdentityLabel,
      List<String> medicalDiets,
      List<DietaryIdentityExceptionDto> exceptions,
      List<HardIntoleranceDto> intolerances,
      List<AgeRestrictionDto> ageRestrictions) {

    static Snapshot of(HardConstraints aggregate) {
      List<DietaryIdentityExceptionDto> exceptions = new ArrayList<>();
      if (aggregate.getExceptions() != null) {
        for (DietaryIdentityException e : aggregate.getExceptions()) {
          exceptions.add(
              new DietaryIdentityExceptionDto(e.getAllows(), e.getFrequency(), e.getContext()));
        }
        exceptions.sort(DIETARY_IDENTITY_EXCEPTION_ORDER);
      }
      List<HardIntoleranceDto> intolerances = new ArrayList<>();
      if (aggregate.getIntolerances() != null) {
        for (HardIntolerance i : aggregate.getIntolerances()) {
          intolerances.add(new HardIntoleranceDto(i.getSubstance(), i.getSeverity(), i.getNotes()));
        }
        intolerances.sort(HARD_INTOLERANCE_ORDER);
      }
      List<AgeRestrictionDto> ageRestrictions = new ArrayList<>();
      if (aggregate.getAgeRestrictions() != null) {
        for (AgeRestriction r : aggregate.getAgeRestrictions()) {
          ageRestrictions.add(new AgeRestrictionDto(r.getRuleKey(), r.isAutoPopulated()));
        }
        ageRestrictions.sort(AGE_RESTRICTION_ORDER);
      }
      List<String> allergies =
          aggregate.getAllergies() == null
              ? Collections.emptyList()
              : new ArrayList<>(aggregate.getAllergies());
      List<String> medicalDiets =
          aggregate.getMedicalDiets() == null
              ? Collections.emptyList()
              : new ArrayList<>(aggregate.getMedicalDiets());
      return new Snapshot(
          allergies,
          aggregate.getDietaryIdentityBase(),
          aggregate.getDietaryIdentityLabel(),
          medicalDiets,
          exceptions,
          intolerances,
          ageRestrictions);
    }

    /** Ordered set of field names that changed between this snapshot and {@code other}. */
    Set<String> diff(Snapshot other) {
      Set<String> changed = new LinkedHashSet<>();
      if (!Objects.equals(this.allergies, other.allergies)) {
        changed.add(FIELD_ALLERGIES);
      }
      if (!Objects.equals(this.dietaryIdentityBase, other.dietaryIdentityBase)) {
        changed.add(FIELD_DIETARY_IDENTITY_BASE);
      }
      if (!Objects.equals(this.dietaryIdentityLabel, other.dietaryIdentityLabel)) {
        changed.add(FIELD_DIETARY_IDENTITY_LABEL);
      }
      if (!Objects.equals(this.medicalDiets, other.medicalDiets)) {
        changed.add(FIELD_MEDICAL_DIETS);
      }
      if (!Objects.equals(this.exceptions, other.exceptions)) {
        changed.add(FIELD_DIETARY_IDENTITY_EXCEPTIONS);
      }
      if (!Objects.equals(this.intolerances, other.intolerances)) {
        changed.add(FIELD_INTOLERANCES);
      }
      if (!Objects.equals(this.ageRestrictions, other.ageRestrictions)) {
        changed.add(FIELD_AGE_RESTRICTIONS);
      }
      return changed;
    }

    /** Project this snapshot's value for a given field into a JsonNode for the audit row. */
    JsonNode toJson(String field, ObjectMapper objectMapper) {
      try {
        Object value =
            switch (field) {
              case FIELD_ALLERGIES -> allergies;
              case FIELD_DIETARY_IDENTITY_BASE -> dietaryIdentityBase;
              case FIELD_DIETARY_IDENTITY_LABEL -> dietaryIdentityLabel;
              case FIELD_MEDICAL_DIETS -> medicalDiets;
              case FIELD_DIETARY_IDENTITY_EXCEPTIONS -> exceptions;
              case FIELD_INTOLERANCES -> intolerances;
              case FIELD_AGE_RESTRICTIONS -> ageRestrictions;
              default -> throw new IllegalStateException("Unknown field: " + field);
            };
        return objectMapper.valueToTree(value);
      } catch (IllegalArgumentException ex) {
        throw new IllegalStateException("Failed to serialize field " + field, ex);
      }
    }

    private static final java.util.Comparator<DietaryIdentityExceptionDto>
        DIETARY_IDENTITY_EXCEPTION_ORDER =
            java.util.Comparator.comparing(
                    DietaryIdentityExceptionDto::allows,
                    java.util.Comparator.nullsFirst(String::compareTo))
                .thenComparing(
                    DietaryIdentityExceptionDto::frequency,
                    java.util.Comparator.nullsFirst(String::compareTo))
                .thenComparing(
                    DietaryIdentityExceptionDto::context,
                    java.util.Comparator.nullsFirst(String::compareTo));

    private static final java.util.Comparator<HardIntoleranceDto> HARD_INTOLERANCE_ORDER =
        java.util.Comparator.comparing(
                HardIntoleranceDto::substance, java.util.Comparator.nullsFirst(String::compareTo))
            .thenComparing(
                HardIntoleranceDto::severity, java.util.Comparator.nullsFirst(String::compareTo))
            .thenComparing(
                HardIntoleranceDto::notes, java.util.Comparator.nullsFirst(String::compareTo));

    private static final java.util.Comparator<AgeRestrictionDto> AGE_RESTRICTION_ORDER =
        java.util.Comparator.comparing(
                AgeRestrictionDto::ruleKey, java.util.Comparator.nullsFirst(String::compareTo))
            .thenComparing(AgeRestrictionDto::autoPopulated);
  }
}
