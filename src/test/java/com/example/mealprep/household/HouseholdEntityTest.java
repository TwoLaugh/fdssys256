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
}
