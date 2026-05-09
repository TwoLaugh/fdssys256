package com.example.mealprep.household.domain.service.internal;

import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.api.dto.HouseholdDto;
import com.example.mealprep.household.api.mapper.HouseholdMapper;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.domain.repository.HouseholdMemberRepository;
import com.example.mealprep.household.domain.repository.HouseholdRepository;
import com.example.mealprep.household.domain.service.HouseholdQueryService;
import com.example.mealprep.household.domain.service.HouseholdUpdateService;
import com.example.mealprep.household.event.HouseholdCreatedEvent;
import com.example.mealprep.household.exception.UserAlreadyInHouseholdException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>{@link HouseholdCreatedEvent} is published synchronously inside the transaction; the event is
 * captured by {@code @TransactionalEventListener(phase = AFTER_COMMIT)} listeners — none in 01a.
 */
@Service
public class HouseholdServiceImpl implements HouseholdQueryService, HouseholdUpdateService {

  private static final Logger log = LoggerFactory.getLogger(HouseholdServiceImpl.class);

  private static final String MDC_TRACE_ID = "traceId";

  private final HouseholdRepository householdRepository;
  private final HouseholdMemberRepository householdMemberRepository;
  private final HouseholdMapper mapper;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;

  public HouseholdServiceImpl(
      HouseholdRepository householdRepository,
      HouseholdMemberRepository householdMemberRepository,
      HouseholdMapper mapper,
      ApplicationEventPublisher eventPublisher,
      Clock clock) {
    this.householdRepository = householdRepository;
    this.householdMemberRepository = householdMemberRepository;
    this.mapper = mapper;
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

    eventPublisher.publishEvent(
        new HouseholdCreatedEvent(saved.getId(), creatorUserId, currentTraceId(), now));

    log.info("household created householdId={} createdByUserId={}", saved.getId(), creatorUserId);
    return mapper.toDto(saved);
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
