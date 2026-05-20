package com.example.mealprep.household;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdInvite;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import com.example.mealprep.household.testdata.HouseholdTestData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the household aggregate's hand-written behaviour and Lombok-generated
 * accessors that Pitest flagged as having SURVIVED return-value mutants (no test asserted the
 * round-tripped value). Covers {@link Household#replaceMembers} (clear + re-parent + null guard),
 * the {@code @Builder.Default} members list, and the id / issuedByUserId accessors. JPA-managed
 * timestamp / version fields are exercised by the persistence IT, not here.
 */
class HouseholdEntityTest {

  @Test
  void builder_membersDefaultsToEmptyMutableList() {
    Household h =
        Household.builder()
            .id(UUID.randomUUID())
            .name("H")
            .createdByUserId(UUID.randomUUID())
            .build();

    assertThat(h.getMembers()).isNotNull().isEmpty();
    // Must be mutable (the @Builder.Default new ArrayList<>(), not List.of()).
    h.getMembers().add(HouseholdTestData.member().build());
    assertThat(h.getMembers()).hasSize(1);
  }

  @Test
  void replaceMembers_clearsExistingThenReParentsReplacements() {
    HouseholdMember old = HouseholdTestData.member().withRole(HouseholdRole.primary).build();
    Household h = HouseholdTestData.household().withMember(old).build();
    assertThat(h.getMembers()).containsExactly(old);

    HouseholdMember a = HouseholdTestData.member().withRole(HouseholdRole.member).build();
    HouseholdMember b = HouseholdTestData.member().withRole(HouseholdRole.member).build();
    h.replaceMembers(List.of(a, b));

    assertThat(h.getMembers()).containsExactly(a, b);
    // Each replacement must have had its back-reference set to this aggregate.
    assertThat(a.getHousehold()).isSameAs(h);
    assertThat(b.getHousehold()).isSameAs(h);
    // Collection identity is preserved (Hibernate requirement) — same List instance.
    List<HouseholdMember> before = h.getMembers();
    h.replaceMembers(new ArrayList<>());
    assertThat(h.getMembers()).isSameAs(before).isEmpty();
  }

  @Test
  void replaceMembers_nullArgument_justClears_noNpe() {
    Household h =
        HouseholdTestData.household().withMember(HouseholdTestData.member().build()).build();

    h.replaceMembers(null);

    assertThat(h.getMembers()).isEmpty();
  }

  @Test
  void household_accessorsReturnBuilderSetValues() {
    UUID id = UUID.randomUUID();
    UUID creator = UUID.randomUUID();
    Household h = Household.builder().id(id).name("Smiths").createdByUserId(creator).build();

    assertThat(h.getId()).isEqualTo(id);
    assertThat(h.getName()).isEqualTo("Smiths");
    assertThat(h.getCreatedByUserId()).isEqualTo(creator);
  }

  @Test
  void householdMember_accessorsReturnBuilderSetValues() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    HouseholdMember m =
        HouseholdTestData.member()
            .withId(id)
            .withUserId(userId)
            .withRole(HouseholdRole.primary)
            .withPriority(250)
            .build();

    assertThat(m.getId()).isEqualTo(id);
    assertThat(m.getUserId()).isEqualTo(userId);
    assertThat(m.getRole()).isEqualTo(HouseholdRole.primary);
    assertThat(m.getPriority()).isEqualTo(250);
  }

  @Test
  void householdInvite_accessorsReturnBuilderSetValues() {
    UUID id = UUID.randomUUID();
    UUID issuedBy = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    HouseholdInvite invite =
        HouseholdTestData.invite()
            .withId(id)
            .withHouseholdId(householdId)
            .withIssuedByUserId(issuedBy)
            .withInviteCode("ABCDEFGH12345678")
            .build();

    assertThat(invite.getId()).isEqualTo(id);
    assertThat(invite.getHouseholdId()).isEqualTo(householdId);
    assertThat(invite.getIssuedByUserId()).isEqualTo(issuedBy);
    assertThat(invite.getInviteCode()).isEqualTo("ABCDEFGH12345678");
  }

  /**
   * Lombok-generated lifecycle accessors (createdAt/updatedAt/version) are normally written by
   * Hibernate at flush time — uncovered by pure-unit tests. We exercise them directly via the
   * builder + Setter so the NullReturnVals / PrimitiveReturns mutants on the Lombok getters land
   * (kills the {@code Household::getCreatedAt}, {@code getUpdatedAt}, {@code getVersion} return
   * mutants).
   */
  @Test
  void household_lifecycleFields_roundTripThroughLombokAccessors() {
    java.time.Instant createdAt = java.time.Instant.parse("2026-05-09T10:00:00Z");
    java.time.Instant updatedAt = java.time.Instant.parse("2026-05-10T11:00:00Z");
    com.example.mealprep.household.domain.entity.Household h =
        com.example.mealprep.household.domain.entity.Household.builder()
            .id(UUID.randomUUID())
            .name("Lifecycle")
            .createdByUserId(UUID.randomUUID())
            .version(7L)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();

    assertThat(h.getVersion()).isEqualTo(7L);
    assertThat(h.getCreatedAt()).isEqualTo(createdAt);
    assertThat(h.getUpdatedAt()).isEqualTo(updatedAt);

    // Round-trip via setters too — Lombok's @Setter on these fields exists for Hibernate.
    h.setVersion(9L);
    h.setCreatedAt(createdAt.plusSeconds(60));
    h.setUpdatedAt(updatedAt.plusSeconds(60));
    assertThat(h.getVersion()).isEqualTo(9L);
    assertThat(h.getCreatedAt()).isEqualTo(createdAt.plusSeconds(60));
    assertThat(h.getUpdatedAt()).isEqualTo(updatedAt.plusSeconds(60));
  }

  /** Lifecycle round-trip for {@link HouseholdMember}. */
  @Test
  void householdMember_lifecycleFields_roundTripThroughLombokAccessors() {
    java.time.Instant createdAt = java.time.Instant.parse("2026-05-09T10:00:00Z");
    java.time.Instant updatedAt = java.time.Instant.parse("2026-05-10T11:00:00Z");
    HouseholdMember m =
        HouseholdMember.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .role(HouseholdRole.member)
            .priority(150)
            .joinedAt(createdAt)
            .version(3L)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();

    assertThat(m.getVersion()).isEqualTo(3L);
    assertThat(m.getCreatedAt()).isEqualTo(createdAt);
    assertThat(m.getUpdatedAt()).isEqualTo(updatedAt);
    assertThat(m.getJoinedAt()).isEqualTo(createdAt);

    m.setVersion(10L);
    assertThat(m.getVersion()).isEqualTo(10L);
  }

  /** Lifecycle round-trip for {@link HouseholdInvite}. */
  @Test
  void householdInvite_lifecycleFields_roundTripThroughLombokAccessors() {
    java.time.Instant createdAt = java.time.Instant.parse("2026-05-09T10:00:00Z");
    HouseholdInvite invite =
        HouseholdInvite.builder()
            .id(UUID.randomUUID())
            .householdId(UUID.randomUUID())
            .inviteCode("CODECODE12345678")
            .issuedByUserId(UUID.randomUUID())
            .intendedRole(HouseholdRole.member)
            .expiresAt(createdAt.plusSeconds(86400))
            .version(2L)
            .createdAt(createdAt)
            .build();

    assertThat(invite.getVersion()).isEqualTo(2L);
    assertThat(invite.getCreatedAt()).isEqualTo(createdAt);
    assertThat(invite.getIntendedRole()).isEqualTo(HouseholdRole.member);
    assertThat(invite.getExpiresAt()).isEqualTo(createdAt.plusSeconds(86400));
    assertThat(invite.getAcceptedAt()).isNull();
    assertThat(invite.getRevokedAt()).isNull();
  }

  /**
   * {@link com.example.mealprep.household.domain.entity.HouseholdSettings} lifecycle fields
   * + @Setter round-trip. Exercises every getter the pitest baseline flagged.
   */
  @Test
  void householdSettings_lifecycleFields_roundTripThroughLombokAccessors() {
    UUID id = UUID.randomUUID();
    UUID householdId = UUID.randomUUID();
    java.time.Instant createdAt = java.time.Instant.parse("2026-05-09T10:00:00Z");
    java.time.Instant updatedAt = java.time.Instant.parse("2026-05-10T11:00:00Z");
    com.example.mealprep.household.domain.entity.HouseholdSettingsDocument doc =
        HouseholdTestData.defaultDocument();
    com.example.mealprep.household.domain.entity.HouseholdSettings s =
        com.example.mealprep.household.domain.entity.HouseholdSettings.builder()
            .id(id)
            .householdId(householdId)
            .document(doc)
            .version(5L)
            .createdAt(createdAt)
            .updatedAt(updatedAt)
            .build();

    assertThat(s.getId()).isEqualTo(id);
    assertThat(s.getHouseholdId()).isEqualTo(householdId);
    assertThat(s.getDocument()).isSameAs(doc);
    assertThat(s.getVersion()).isEqualTo(5L);
    assertThat(s.getCreatedAt()).isEqualTo(createdAt);
    assertThat(s.getUpdatedAt()).isEqualTo(updatedAt);

    // Round-trip through @Setter — HouseholdServiceImpl uses setDocument to persist new state.
    com.example.mealprep.household.domain.entity.HouseholdSettingsDocument nextDoc =
        new com.example.mealprep.household.domain.entity.HouseholdSettingsDocument(
            doc.slotDefaults(), doc.customSlots(), 7, doc.scheduling());
    s.setDocument(nextDoc);
    s.setVersion(6L);
    s.setUpdatedAt(updatedAt.plusSeconds(60));
    assertThat(s.getDocument()).isSameAs(nextDoc);
    assertThat(s.getVersion()).isEqualTo(6L);
    assertThat(s.getUpdatedAt()).isEqualTo(updatedAt.plusSeconds(60));
  }

  /** {@link com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog} accessors. */
  @Test
  void householdSettingsAuditLog_accessors_roundTrip() {
    UUID id = UUID.randomUUID();
    UUID settingsId = UUID.randomUUID();
    UUID actorUserId = UUID.randomUUID();
    com.fasterxml.jackson.databind.JsonNode prevVal =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(2);
    com.fasterxml.jackson.databind.JsonNode newVal =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(4);
    java.time.Instant occurredAt = java.time.Instant.parse("2026-05-09T10:00:00Z");
    com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog row =
        com.example.mealprep.household.domain.entity.HouseholdSettingsAuditLog.builder()
            .id(id)
            .householdSettingsId(settingsId)
            .actorUserId(actorUserId)
            .fieldPath("defaultHeadcount")
            .previousValueJson(prevVal)
            .newValueJson(newVal)
            .occurredAt(occurredAt)
            .build();

    assertThat(row.getId()).isEqualTo(id);
    assertThat(row.getHouseholdSettingsId()).isEqualTo(settingsId);
    assertThat(row.getActorUserId()).isEqualTo(actorUserId);
    assertThat(row.getFieldPath()).isEqualTo("defaultHeadcount");
    assertThat(row.getPreviousValueJson()).isSameAs(prevVal);
    assertThat(row.getNewValueJson()).isSameAs(newVal);
    assertThat(row.getOccurredAt()).isEqualTo(occurredAt);
  }
}
