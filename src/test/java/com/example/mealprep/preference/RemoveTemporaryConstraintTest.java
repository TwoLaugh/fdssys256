package com.example.mealprep.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.mealprep.preference.api.mapper.HardConstraintsMapper;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardConstraintsAuditLog;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import com.example.mealprep.preference.domain.repository.HardConstraintsAuditLogRepository;
import com.example.mealprep.preference.domain.repository.HardConstraintsRepository;
import com.example.mealprep.preference.domain.repository.HardIntoleranceRepository;
import com.example.mealprep.preference.domain.service.internal.PreferenceServiceImpl;
import com.example.mealprep.preference.event.HardConstraintsUpdatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link PreferenceServiceImpl#removeTemporaryConstraint} — the directive
 * auto-expiry reversal surface (nutrition/01j). Repositories + event publisher are mocked at the
 * module boundary; a real {@link ObjectMapper} serialises the audit payloads.
 */
@ExtendWith(MockitoExtension.class)
class RemoveTemporaryConstraintTest {

  @Mock private HardConstraintsRepository hardConstraintsRepository;
  @Mock private HardConstraintsAuditLogRepository auditLogRepository;
  @Mock private HardIntoleranceRepository hardIntoleranceRepository;
  @Mock private ApplicationEventPublisher eventPublisher;

  private final HardConstraintsMapper mapper =
      new com.example.mealprep.preference.api.mapper.HardConstraintsMapperImpl();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Instant fixedNow = Instant.parse("2026-07-15T10:00:00Z");
  private final Clock fixedClock = Clock.fixed(fixedNow, ZoneOffset.UTC);

  private UUID userId;
  private UUID directiveId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    directiveId = UUID.randomUUID();
  }

  private PreferenceServiceImpl service() {
    return new PreferenceServiceImpl(
        hardConstraintsRepository,
        auditLogRepository,
        hardIntoleranceRepository,
        mapper,
        eventPublisher,
        objectMapper,
        fixedClock);
  }

  private HardConstraints aggregateWith(List<HardIntolerance> intolerances) {
    HardConstraints aggregate =
        HardConstraints.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .allergies(new ArrayList<>())
            .dietaryIdentityBase("omnivore")
            .medicalDiets(new ArrayList<>())
            .exceptions(new ArrayList<>())
            .intolerances(new ArrayList<>())
            .ageRestrictions(new ArrayList<>())
            .build();
    for (HardIntolerance i : intolerances) {
      i.setHardConstraints(aggregate);
      aggregate.getIntolerances().add(i);
    }
    return aggregate;
  }

  private HardIntolerance directiveSourced(String substance) {
    return HardIntolerance.builder()
        .id(UUID.randomUUID())
        .substance(substance)
        .severity("avoid")
        .sourceDirectiveId(directiveId)
        .autoExpiresAt(fixedNow.minusSeconds(10))
        .build();
  }

  private HardIntolerance userAuthored(String substance) {
    return HardIntolerance.builder()
        .id(UUID.randomUUID())
        .substance(substance)
        .severity("moderate")
        .build();
  }

  // ---------------- reverse ----------------

  @Test
  void removesTheDirectiveRow_writesAudit_bumpsVersion_publishesEvent() {
    HardIntolerance directiveRow = directiveSourced("egg");
    HardIntolerance keep = userAuthored("lactose");
    HardConstraints aggregate = aggregateWith(List.of(keep, directiveRow));
    when(hardIntoleranceRepository.findBySourceDirectiveId(directiveId))
        .thenReturn(List.of(directiveRow));
    when(hardConstraintsRepository.findByUserId(userId)).thenReturn(Optional.of(aggregate));
    when(hardConstraintsRepository.saveAndFlush(aggregate)).thenReturn(aggregate);

    service().removeTemporaryConstraint(userId, directiveId);

    // Only the user-authored row survives.
    assertThat(aggregate.getIntolerances()).containsExactly(keep);

    ArgumentCaptor<HardConstraintsAuditLog> auditCap =
        ArgumentCaptor.forClass(HardConstraintsAuditLog.class);
    verify(auditLogRepository).save(auditCap.capture());
    HardConstraintsAuditLog audit = auditCap.getValue();
    assertThat(audit.getFieldChanged()).isEqualTo("intolerances");
    assertThat(audit.getActorUserId()).isEqualTo(userId);
    assertThat(audit.getOccurredAt()).isEqualTo(fixedNow);
    // previous had both substances; new has only the kept one.
    assertThat(audit.getPreviousValueJson().toString()).contains("egg").contains("lactose");
    assertThat(audit.getNewValueJson().toString()).contains("lactose").doesNotContain("egg");

    verify(hardConstraintsRepository).saveAndFlush(aggregate);
    ArgumentCaptor<HardConstraintsUpdatedEvent> evtCap =
        ArgumentCaptor.forClass(HardConstraintsUpdatedEvent.class);
    verify(eventPublisher).publishEvent(evtCap.capture());
    assertThat(evtCap.getValue().userId()).isEqualTo(userId);
    assertThat(evtCap.getValue().fieldsChanged()).containsExactly("intolerances");
  }

  // ---------------- idempotent ----------------

  @Test
  void noSurvivingRows_isNoOp_noAuditNoEventNoThrow() {
    when(hardIntoleranceRepository.findBySourceDirectiveId(directiveId)).thenReturn(List.of());

    service().removeTemporaryConstraint(userId, directiveId);

    verify(auditLogRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
    verify(hardConstraintsRepository, never()).saveAndFlush(any());
  }

  @Test
  void rowsBelongToAnotherUsersAggregate_isNoOp() {
    HardIntolerance directiveRow = directiveSourced("egg");
    // The directive row is parented to a DIFFERENT aggregate than the userId's.
    HardConstraints otherAggregate = aggregateWith(List.of(directiveRow));
    HardConstraints thisUsersAggregate = aggregateWith(new ArrayList<>());
    when(hardIntoleranceRepository.findBySourceDirectiveId(directiveId))
        .thenReturn(List.of(directiveRow));
    when(hardConstraintsRepository.findByUserId(userId))
        .thenReturn(Optional.of(thisUsersAggregate));

    service().removeTemporaryConstraint(userId, directiveId);

    assertThat(otherAggregate.getIntolerances()).containsExactly(directiveRow);
    verify(auditLogRepository, never()).save(any());
    verify(eventPublisher, never()).publishEvent(any());
  }
}
