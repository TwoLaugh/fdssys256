package com.example.mealprep.household.testdata;

import com.example.mealprep.household.api.dto.CreateHouseholdRequest;
import com.example.mealprep.household.domain.entity.Household;
import com.example.mealprep.household.domain.entity.HouseholdMember;
import com.example.mealprep.household.domain.entity.HouseholdRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test Data Builder for the household module's aggregate. Defaults match the validator constraints
 * so callers tweak only the field under test.
 */
public final class HouseholdTestData {

  private HouseholdTestData() {}

  public static HouseholdBuilder household() {
    return new HouseholdBuilder();
  }

  public static MemberBuilder member() {
    return new MemberBuilder();
  }

  public static CreateHouseholdRequest createRequest() {
    return new CreateHouseholdRequest("My Household");
  }

  public static CreateHouseholdRequest createRequest(String name) {
    return new CreateHouseholdRequest(name);
  }

  public static final class HouseholdBuilder {
    private UUID id = UUID.randomUUID();
    private String name = "My Household";
    private UUID createdByUserId = UUID.randomUUID();
    private final List<HouseholdMember> members = new ArrayList<>();

    public HouseholdBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    public HouseholdBuilder withName(String name) {
      this.name = name;
      return this;
    }

    public HouseholdBuilder withCreatedByUserId(UUID createdByUserId) {
      this.createdByUserId = createdByUserId;
      return this;
    }

    public HouseholdBuilder withMember(HouseholdMember member) {
      this.members.add(member);
      return this;
    }

    public Household build() {
      Household household =
          Household.builder()
              .id(id)
              .name(name)
              .createdByUserId(createdByUserId)
              .members(new ArrayList<>())
              .build();
      for (HouseholdMember m : members) {
        m.setHousehold(household);
        household.getMembers().add(m);
      }
      return household;
    }
  }

  public static final class MemberBuilder {
    private UUID id = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private HouseholdRole role = HouseholdRole.primary;
    private String displayName;
    private int priority = 100;
    private Instant joinedAt = Instant.parse("2026-05-08T10:00:00Z");

    public MemberBuilder withId(UUID id) {
      this.id = id;
      return this;
    }

    public MemberBuilder withUserId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public MemberBuilder withRole(HouseholdRole role) {
      this.role = role;
      return this;
    }

    public MemberBuilder withDisplayName(String displayName) {
      this.displayName = displayName;
      return this;
    }

    public MemberBuilder withPriority(int priority) {
      this.priority = priority;
      return this;
    }

    public MemberBuilder withJoinedAt(Instant joinedAt) {
      this.joinedAt = joinedAt;
      return this;
    }

    public HouseholdMember build() {
      return HouseholdMember.builder()
          .id(id)
          .userId(userId)
          .role(role)
          .displayName(displayName)
          .priority(priority)
          .joinedAt(joinedAt)
          .build();
    }
  }
}
