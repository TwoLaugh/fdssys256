package com.example.mealprep.household.domain.service.internal;

import com.example.mealprep.household.api.dto.AcceptInviteRequest;
import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.CreateInviteRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.dto.HouseholdInviteDto;
import com.example.mealprep.household.api.dto.HouseholdMemberDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsAuditEntryDto;
import com.example.mealprep.household.api.dto.HouseholdSettingsDto;
import com.example.mealprep.household.api.dto.SlotConfigurationDto;
import com.example.mealprep.household.api.dto.UpdateHouseholdSettingsRequest;
import com.example.mealprep.household.api.mapper.HouseholdInviteMapper;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.api.mapper.HouseholdMemberMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsAuditMapper;
import com.example.mealprep.household.api.mapper.HouseholdSettingsMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdInvite;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.entity.HouseholdSettings;
import com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.HouseholdSchedulingPreferences;
import com.example.mealprep.household.domain.entity.HouseholdSettingsDocument.SlotDefault;
import com.example.mealprep.household.domain.entity.SlotKind;
import com.example.mealprep.household.domain.repository.HouseholdInviteRepository;
import com.example.mealprep.household.domain.repository.HouseholdMemberRepository;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsAuditLogRepository;
import com.example.mealprep.household.domain.repository.HouseholdSettingsRepository;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.event.HouseholdCreatedEvent;
import com.example.mealprep.household.event.HouseholdInviteAcceptedEvent;
import com.example.mealprep.household.event.HouseholdInviteCreatedEvent;
import com.example.mealprep.household.event.HouseholdSettingsChangedEvent;
import com.example.mealprep.household.exception.HouseholdInviteAlreadyAcceptedException;
import com.example.mealprep.household.exception.HouseholdInviteExpiredException;
import com.example.mealprep.household.exception.HouseholdInviteNotFoundException;
import com.example.mealprep.household.exception.HouseholdInviteRevokedException;
import com.example.mealprep.household.exception.HouseholdNotFoundException;
import com.example.mealprep.household.exception.HouseholdSettingsNotFoundException;
import com.example.mealprep.household.exception.InsufficientHouseholdRoleException;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single implementation of {@link HouseholdQueryService} and {@link HouseholdUpdateService}.
 *
 * <p>Reads run with {@code readOnly = true}; writes run REQUIRED (top-level transactions). The
 * create path enforces the v1 single-household-per-user invariant in two layers — a service-level
 * pre-check (returns the friendly 409 ProblemDetail) and the {@code UNIQUE (user_id)} constraint on
 * {@code household_member} (defence-in-depth against TOCTOU on concurrent inserts).
 *
 * <p>{@link HouseholdCreatedEvent} and {@link HouseholdSettingsChangedEvent} are published
 * synchronously inside their transactions; downstream listeners attach with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} — no listeners in the household module
 * itself in 01b.
 */
@Service
public class HouseholdServiceImpl implements HouseholdQueryService, HouseholdUpdateService {

  private static final Logger log = LoggerFactory.getLogger(HouseholdServiceImpl.class);

  private static final String MDC_TRACE_ID = "traceId";

  /** Hard ceiling on invite lifetime (LLD-pinned). Truncates request {@code expiresAt} silently. */
  private static final Duration MAX_INVITE_LIFETIME = Duration.ofDays(30);

  /** Cap on how many code-collision retries we attempt before propagating the exception. */
  private static final int INVITE_CODE_COLLISION_RETRIES = 3;

  private final HouseholdRepository householdRepository;
  private final HouseholdMemberRepository householdMemberRepository;
  private final HouseholdSettingsRepository householdSettingsRepository;
  private final HouseholdSettingsAuditLogRepository householdSettingsAuditLogRepository;
  private final HouseholdInviteRepository householdInviteRepository;
  private final HouseholdMapper mapper;
  private final HouseholdMemberMapper memberMapper;
  private final HouseholdSettingsMapper settingsMapper;
  private final HouseholdSettingsAuditMapper settingsAuditMapper;
  private final HouseholdInviteMapper inviteMapper;
  private final HouseholdSettingsDiffer differ;
  private final SlotConfigurationResolver slotConfigurationResolver;
  private final InviteCodeGenerator inviteCodeGenerator;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public HouseholdServiceImpl(
      HouseholdRepository householdRepository,
      HouseholdMemberRepository householdMemberRepository,
      HouseholdSettingsRepository householdSettingsRepository,
      HouseholdSettingsAuditLogRepository householdSettingsAuditLogRepository,
      HouseholdInviteRepository householdInviteRepository,
      HouseholdMapper mapper,
      HouseholdMemberMapper memberMapper,
      HouseholdSettingsMapper settingsMapper,
      HouseholdSettingsAuditMapper settingsAuditMapper,
      HouseholdInviteMapper inviteMapper,
      HouseholdSettingsDiffer differ,
      SlotConfigurationResolver slotConfigurationResolver,
      InviteCodeGenerator inviteCodeGenerator,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.householdRepository = householdRepository;
    this.householdMemberRepository = householdMemberRepository;
    this.householdSettingsRepository = householdSettingsRepository;
    this.householdSettingsAuditLogRepository = householdSettingsAuditLogRepository;
    this.householdInviteRepository = householdInviteRepository;
    this.mapper = mapper;
    this.memberMapper = memberMapper;
    this.settingsMapper = settingsMapper;
    this.settingsAuditMapper = settingsAuditMapper;
    this.inviteMapper = inviteMapper;
    this.differ = differ;
    this.slotConfigurationResolver = slotConfigurationResolver;
    this.inviteCodeGenerator = inviteCodeGenerator;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  // ---------------- Query ----------------

  @Override
  @Transactional(readOnly = true)
  public Optional<HouseholdDto> getById(UUID householdId) {
    return householdRepository.findWithMembersById(householdId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<HouseholdDto> getByUserId(UUID userId) {
    Optional<HouseholdMember> memberOpt = householdMemberRepository.findByUserId(userId);
    if (memberOpt.isEmpty()) {
      return Optional.empty();
    }
    UUID householdId = memberOpt.get().getHousehold().getId();
    return householdRepository.findWithMembersById(householdId).map(mapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<HouseholdSettingsDto> getSettings(UUID householdId, UUID callerUserId) {
    if (!isMember(householdId, callerUserId)) {
      // Non-member: 404 (don't leak existence).
      return Optional.empty();
    }
    return householdSettingsRepository.findByHouseholdId(householdId).map(settingsMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<HouseholdSettingsAuditEntryDto> getSettingsAuditLog(
      UUID householdId, UUID callerUserId, Pageable pageable) {
    if (!isMember(householdId, callerUserId)) {
      // Non-member: surface as 404 to avoid leaking existence.
      throw new HouseholdSettingsNotFoundException(householdId);
    }
    HouseholdSettings settings =
        householdSettingsRepository
            .findByHouseholdId(householdId)
            .orElseThrow(() -> new HouseholdSettingsNotFoundException(householdId));
    return householdSettingsAuditLogRepository
        .findByHouseholdSettingsIdOrderByOccurredAtDesc(settings.getId(), pageable)
        .map(settingsAuditMapper::toDto);
  }

  @Override
  @Transactional(readOnly = true)
  public SlotConfigurationDto getSlotConfiguration(UUID householdId, UUID callerUserId) {
    if (!isMember(householdId, callerUserId)) {
      throw new HouseholdNotFoundException(callerUserId);
    }
    Household household =
        householdRepository
            .findWithMembersById(householdId)
            .orElseThrow(() -> new HouseholdNotFoundException(callerUserId));
    HouseholdSettings settings =
        householdSettingsRepository
            .findByHouseholdId(householdId)
            .orElseThrow(() -> new HouseholdSettingsNotFoundException(householdId));
    return slotConfigurationResolver.resolve(household, settings);
  }

  @Override
  @Transactional(readOnly = true)
  public List<HouseholdInviteDto> listPendingInvites(UUID householdId) {
    List<HouseholdInvite> rows =
        householdInviteRepository
            .findByHouseholdIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(
                householdId);
    return inviteMapper.toDtosCodeRedacted(rows);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<HouseholdInviteDto> getInviteByCode(String inviteCode) {
    if (inviteCode == null || inviteCode.isBlank()) {
      return Optional.empty();
    }
    return householdInviteRepository.findByInviteCode(inviteCode).map(inviteMapper::toDtoWithCode);
  }

  // ---------------- Update ----------------

  @Override
  @Transactional
  public HouseholdDto createHousehold(UUID creatorUserId, CreateHouseholdRequest request) {
    if (householdMemberRepository.findByUserId(creatorUserId).isPresent()) {
      throw new UserAlreadyInHouseholdException(creatorUserId);
    }

    Instant now = Instant.now(clock);
    UUID householdId = UUID.randomUUID();

    Household household =
        Household.builder()
            .id(householdId)
            .name(request.name())
            .createdByUserId(creatorUserId)
            .members(new ArrayList<>())
            .build();

    HouseholdMember primaryMember =
        HouseholdMember.builder()
            .id(UUID.randomUUID())
            .household(household)
            .userId(creatorUserId)
            .role(HouseholdRole.primary)
            .displayName(null)
            .priority(100)
            .joinedAt(now)
            .build();

    household.getMembers().add(primaryMember);

    // saveAndFlush so {@code @CreationTimestamp} ({@code createdAt}) and the {@code @Version}
    // bump materialise before we map to DTO; otherwise the response carries null timestamps and
    // the OpenAPI schema rejects the response (createdAt is required + non-null).
    Household saved = householdRepository.saveAndFlush(household);

    // 01b: seed a default settings row in the same transaction so subsequent GET /settings calls
    // succeed without an explicit PUT-to-seed.
    householdSettingsRepository.save(buildDefaultSettings(saved.getId()));

    eventPublisher.publishEvent(
        new HouseholdCreatedEvent(saved.getId(), creatorUserId, currentTraceId(), now));

    log.info("household created householdId={} createdByUserId={}", saved.getId(), creatorUserId);
    return mapper.toDto(saved);
  }

  @Override
  @Transactional
  public HouseholdSettingsDto updateSettings(
      UUID householdId, UUID actorUserId, UpdateHouseholdSettingsRequest request) {
    HouseholdMember caller =
        householdMemberRepository
            .findByUserId(actorUserId)
            .filter(m -> m.getHousehold() != null && householdId.equals(m.getHousehold().getId()))
            .orElseThrow(
                () ->
                    new InsufficientHouseholdRoleException(
                        "caller is not a member of household " + householdId));
    if (caller.getRole() != HouseholdRole.primary) {
      throw new InsufficientHouseholdRoleException(
          "primary role required to update settings of household " + householdId);
    }

    HouseholdSettings existing =
        householdSettingsRepository
            .findByHouseholdId(householdId)
            .orElseThrow(() -> new HouseholdSettingsNotFoundException(householdId));

    if (existing.getVersion() != request.expectedVersion()) {
      throw new OptimisticLockingFailureException(
          "stale expectedVersion for household_settings id=" + existing.getId());
    }

    Set<String> changedPaths = new LinkedHashSet<>();
    List<HouseholdSettingsAuditLog> auditRows =
        differ.diff(
            existing.getId(),
            actorUserId,
            existing.getDocument(),
            request.document(),
            changedPaths);

    if (changedPaths.isEmpty()) {
      // No-op replacement: do not bump @Version, do not write audit rows, do not publish an event.
      return settingsMapper.toDto(existing);
    }

    existing.setDocument(request.document());
    // saveAndFlush so the bumped @Version + @UpdateTimestamp materialise before we map to DTO.
    HouseholdSettings saved = householdSettingsRepository.saveAndFlush(existing);
    householdSettingsAuditLogRepository.saveAll(auditRows);

    eventPublisher.publishEvent(
        new HouseholdSettingsChangedEvent(
            householdId,
            saved.getId(),
            Collections.unmodifiableSet(new LinkedHashSet<>(changedPaths)),
            currentTraceId(),
            Instant.now(clock)));

    log.info(
        "household settings updated householdId={} settingsId={} changedFieldPaths={}",
        householdId,
        saved.getId(),
        changedPaths);
    return settingsMapper.toDto(saved);
  }

  @Override
  @Transactional
  public HouseholdInviteDto createInvite(
      UUID householdId, UUID actorUserId, CreateInviteRequest request) {
    HouseholdMember caller =
        householdMemberRepository
            .findByUserId(actorUserId)
            .orElseThrow(() -> new HouseholdNotFoundException(actorUserId));
    if (caller.getHousehold() == null || !householdId.equals(caller.getHousehold().getId())) {
      // Caller is in a different household: surface as 404 to avoid leaking existence.
      throw new HouseholdNotFoundException(actorUserId);
    }
    if (caller.getRole() != HouseholdRole.primary) {
      throw new InsufficientHouseholdRoleException(
          "primary role required to create an invite for household " + householdId);
    }

    Instant now = Instant.now(clock);
    Instant cap = now.plus(MAX_INVITE_LIFETIME);
    Instant effectiveExpiresAt = request.expiresAt().isAfter(cap) ? cap : request.expiresAt();

    HouseholdInvite saved =
        persistInviteWithCollisionRetry(householdId, actorUserId, request, effectiveExpiresAt);

    eventPublisher.publishEvent(
        new HouseholdInviteCreatedEvent(
            householdId,
            saved.getId(),
            actorUserId,
            request.issuedForUserId(),
            saved.getIntendedRole(),
            saved.getExpiresAt(),
            currentTraceId(),
            Instant.now(clock)));

    log.info(
        "household invite created householdId={} inviteId={} issuedByUserId={} intendedRole={}",
        householdId,
        saved.getId(),
        actorUserId,
        saved.getIntendedRole());

    return inviteMapper.toDtoWithCode(saved);
  }

  @Override
  @Transactional
  public HouseholdMemberDto acceptInvite(UUID accepterUserId, AcceptInviteRequest request) {
    HouseholdInvite invite =
        householdInviteRepository
            .findByInviteCode(request.inviteCode())
            .orElseThrow(() -> new HouseholdInviteNotFoundException("Invite not found"));

    if (invite.getRevokedAt() != null) {
      throw new HouseholdInviteRevokedException("Invite has been revoked");
    }
    if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now(clock))) {
      throw new HouseholdInviteExpiredException("Invite has expired");
    }
    if (invite.getAcceptedAt() != null) {
      throw new HouseholdInviteAlreadyAcceptedException("Invite has already been accepted");
    }
    if (invite.getIssuedForUserId() != null
        && !invite.getIssuedForUserId().equals(accepterUserId)) {
      throw new InsufficientHouseholdRoleException("Invite is for a different user");
    }
    if (householdMemberRepository.findByUserId(accepterUserId).isPresent()) {
      throw new UserAlreadyInHouseholdException(accepterUserId);
    }

    Household household =
        householdRepository
            .findWithMembersById(invite.getHouseholdId())
            .orElseThrow(() -> new HouseholdNotFoundException(invite.getHouseholdId()));

    Instant now = Instant.now(clock);
    HouseholdMember newMember =
        HouseholdMember.builder()
            .id(UUID.randomUUID())
            .household(household)
            .userId(accepterUserId)
            .role(invite.getIntendedRole())
            .displayName(null)
            .priority(100)
            .joinedAt(now)
            .build();
    household.getMembers().add(newMember);

    // saveAndFlush so {@code @CreationTimestamp} ({@code createdAt}) and the {@code @Version}
    // bump materialise before we map to DTO; also forces the partial unique index check on
    // primary-per-household (which maps to 409 via GlobalExceptionHandler if violated).
    Household savedHousehold = householdRepository.saveAndFlush(household);

    invite.setAcceptedAt(now);
    invite.setAcceptedByUserId(accepterUserId);
    HouseholdInvite savedInvite = householdInviteRepository.saveAndFlush(invite);

    HouseholdMember persistedMember =
        savedHousehold.getMembers().stream()
            .filter(m -> accepterUserId.equals(m.getUserId()))
            .findFirst()
            .orElse(newMember);

    eventPublisher.publishEvent(
        new HouseholdInviteAcceptedEvent(
            savedInvite.getHouseholdId(),
            savedInvite.getId(),
            accepterUserId,
            savedInvite.getIntendedRole(),
            currentTraceId(),
            Instant.now(clock)));

    log.info(
        "household invite accepted householdId={} inviteId={} accepterUserId={} grantedRole={}",
        savedInvite.getHouseholdId(),
        savedInvite.getId(),
        accepterUserId,
        savedInvite.getIntendedRole());

    return memberMapper.toDto(persistedMember);
  }

  @Override
  @Transactional
  public void revokeInvite(UUID inviteId, UUID actorUserId) {
    HouseholdInvite invite =
        householdInviteRepository
            .findById(inviteId)
            .orElseThrow(() -> new HouseholdInviteNotFoundException("Invite not found"));

    HouseholdMember caller =
        householdMemberRepository
            .findByUserId(actorUserId)
            .orElseThrow(() -> new HouseholdInviteNotFoundException("Invite not found"));

    UUID callerHouseholdId = caller.getHousehold() == null ? null : caller.getHousehold().getId();
    if (callerHouseholdId == null || !invite.getHouseholdId().equals(callerHouseholdId)) {
      // Don't leak existence of invites belonging to a different household.
      throw new HouseholdInviteNotFoundException("Invite not found");
    }
    if (caller.getRole() != HouseholdRole.primary) {
      throw new InsufficientHouseholdRoleException(
          "primary role required to revoke invites in household " + callerHouseholdId);
    }
    if (invite.getAcceptedAt() != null) {
      throw new HouseholdInviteAlreadyAcceptedException(
          "Invite " + inviteId + " has already been accepted and cannot be revoked");
    }
    if (invite.getRevokedAt() != null) {
      throw new HouseholdInviteAlreadyAcceptedException(
          "Invite " + inviteId + " has already been revoked");
    }

    invite.setRevokedAt(Instant.now(clock));
    householdInviteRepository.saveAndFlush(invite);

    log.info(
        "household invite revoked inviteId={} householdId={} revokedByUserId={}",
        inviteId,
        invite.getHouseholdId(),
        actorUserId);
  }

  // ---------------- helpers ----------------

  /**
   * Insert a new invite row, retrying on {@link DataIntegrityViolationException} from the {@code
   * UNIQUE (invite_code)} constraint. Each retry generates a fresh code. Beyond {@link
   * #INVITE_CODE_COLLISION_RETRIES} retries, propagate the exception (would only happen at
   * multi-billion-row scale).
   */
  private HouseholdInvite persistInviteWithCollisionRetry(
      UUID householdId, UUID actorUserId, CreateInviteRequest request, Instant effectiveExpiresAt) {
    DataIntegrityViolationException last = null;
    for (int attempt = 0; attempt <= INVITE_CODE_COLLISION_RETRIES; attempt++) {
      String code = inviteCodeGenerator.generate();
      HouseholdInvite invite =
          HouseholdInvite.builder()
              .id(UUID.randomUUID())
              .householdId(householdId)
              .inviteCode(code)
              .issuedByUserId(actorUserId)
              .issuedForUserId(request.issuedForUserId())
              .intendedRole(request.intendedRole())
              .expiresAt(effectiveExpiresAt)
              .build();
      try {
        // saveAndFlush so {@code @CreationTimestamp} materialises and the unique-constraint
        // violation surfaces here (rather than at end-of-transaction commit, where we couldn't
        // catch and retry).
        return householdInviteRepository.saveAndFlush(invite);
      } catch (DataIntegrityViolationException ex) {
        last = ex;
        log.warn(
            "invite code collision on attempt {} for householdId={}; retrying",
            attempt + 1,
            householdId);
      }
    }
    throw last == null
        ? new IllegalStateException("invite-code collision retry loop exited without exception")
        : last;
  }

  private boolean isMember(UUID householdId, UUID callerUserId) {
    if (callerUserId == null) {
      return false;
    }
    return householdMemberRepository
        .findByUserId(callerUserId)
        .map(m -> m.getHousehold() != null && householdId.equals(m.getHousehold().getId()))
        .orElse(false);
  }

  /**
   * Build the default settings document for a newly-created household: every built-in slot-kind
   * (breakfast/lunch/dinner/snack) seeded with {@code shared=true, headcount=1, timeBudgetMin=30};
   * empty {@code customSlots}; null {@code defaultHeadcount}; empty {@link
   * HouseholdSchedulingPreferences} marker.
   */
  private HouseholdSettings buildDefaultSettings(UUID householdId) {
    Map<SlotKind, SlotDefault> slotDefaults = new LinkedHashMap<>();
    slotDefaults.put(SlotKind.breakfast, new SlotDefault(true, 1, 30));
    slotDefaults.put(SlotKind.lunch, new SlotDefault(true, 1, 30));
    slotDefaults.put(SlotKind.dinner, new SlotDefault(true, 1, 30));
    slotDefaults.put(SlotKind.snack, new SlotDefault(true, 1, 30));
    HouseholdSettingsDocument document =
        new HouseholdSettingsDocument(
            slotDefaults, new ArrayList<>(), null, new HouseholdSchedulingPreferences());
    return HouseholdSettings.builder()
        .id(UUID.randomUUID())
        .householdId(householdId)
        .document(document)
        .build();
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
}
