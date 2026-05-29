package com.example.mealprep.preference.testdata;

import com.example.mealprep.preference.api.dto.AgeRestrictionDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityDto;
import com.example.mealprep.preference.api.dto.DietaryIdentityExceptionDto;
import com.example.mealprep.preference.api.dto.HardIntoleranceDto;
import com.example.mealprep.preference.api.dto.UpdateHardConstraintsRequest;
import com.example.mealprep.preference.domain.entity.AgeRestriction;
import com.example.mealprep.preference.domain.entity.DietaryIdentityException;
import com.example.mealprep.preference.domain.entity.HardConstraints;
import com.example.mealprep.preference.domain.entity.HardIntolerance;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Test Data Builder for the preference module's hard-constraints aggregate. Defaults match the
 * validator constraints so callers tweak only the field under test.
 */
public final class HardConstraintsTestData {

  private HardConstraintsTestData() {}

  public static HardConstraintsBuilder hardConstraints() {
    return new HardConstraintsBuilder();
  }

  public static UpdateRequestBuilder updateRequest() {
    return new UpdateRequestBuilder();
  }

  public static DietaryIdentityDto omnivoreIdentity() {
    return new DietaryIdentityDto("omnivore", null, List.of());
  }

  public static DietaryIdentityDto vegetarianIdentityWithFishOnWeekends() {
    return new DietaryIdentityDto(
        "vegetarian",
        "Mostly vegetarian",
        List.of(new DietaryIdentityExceptionDto("fish", "weekly", "weekend")));
  }

  public static HardIntoleranceDto lactoseIntolerance() {
    return new HardIntoleranceDto("lactose", "moderate", "Avoid milk-heavy meals");
  }

  public static AgeRestrictionDto noWholeNutsRestriction() {
    return new AgeRestrictionDto("no_whole_nuts", true);
  }

  public static final class HardConstraintsBuilder {
    private UUID id = UUID.randomUUID();
    private UUID userId = UUID.randomUUID();
    private List<String> allergies = new ArrayList<>();
    private String dietaryIdentityBase = "omnivore";
    private String dietaryIdentityLabel;
    private List<String> medicalDiets = new ArrayList<>();
    private List<DietaryIdentityException> exceptions = new ArrayList<>();
    private List<HardIntolerance> intolerances = new ArrayList<>();
    private List<AgeRestriction> ageRestrictions = new ArrayList<>();

    public HardConstraintsBuilder withUserId(UUID userId) {
      this.userId = userId;
      return this;
    }

    public HardConstraintsBuilder withAllergies(String... allergies) {
      this.allergies = new ArrayList<>(List.of(allergies));
      return this;
    }

    public HardConstraintsBuilder withDietaryIdentityBase(String base) {
      this.dietaryIdentityBase = base;
      return this;
    }

    public HardConstraintsBuilder withDietaryIdentityLabel(String label) {
      this.dietaryIdentityLabel = label;
      return this;
    }

    public HardConstraintsBuilder withMedicalDiets(String... diets) {
      this.medicalDiets = new ArrayList<>(List.of(diets));
      return this;
    }

    public HardConstraints build() {
      HardConstraints aggregate =
          HardConstraints.builder()
              .id(id)
              .userId(userId)
              .allergies(allergies)
              .dietaryIdentityBase(dietaryIdentityBase)
              .dietaryIdentityLabel(dietaryIdentityLabel)
              .medicalDiets(medicalDiets)
              .exceptions(new ArrayList<>())
              .intolerances(new ArrayList<>())
              .ageRestrictions(new ArrayList<>())
              .build();
      // Re-link the children so back-references resolve under @ManyToOne(optional=false).
      for (DietaryIdentityException e : exceptions) {
        e.setHardConstraints(aggregate);
        aggregate.getExceptions().add(e);
      }
      for (HardIntolerance i : intolerances) {
        i.setHardConstraints(aggregate);
        aggregate.getIntolerances().add(i);
      }
      for (AgeRestriction r : ageRestrictions) {
        r.setHardConstraints(aggregate);
        aggregate.getAgeRestrictions().add(r);
      }
      return aggregate;
    }
  }

  public static final class UpdateRequestBuilder {
    private List<String> allergies = new ArrayList<>();
    private DietaryIdentityDto dietaryIdentity = omnivoreIdentity();
    private List<String> medicalDiets = new ArrayList<>();
    private List<HardIntoleranceDto> intolerances = new ArrayList<>();
    private List<AgeRestrictionDto> ageRestrictions = new ArrayList<>();
    private long expectedVersion = 0L;
    private Boolean confirmTier1Removals;

    public UpdateRequestBuilder withAllergies(String... allergies) {
      this.allergies = new ArrayList<>(List.of(allergies));
      return this;
    }

    public UpdateRequestBuilder withDietaryIdentity(DietaryIdentityDto identity) {
      this.dietaryIdentity = identity;
      return this;
    }

    public UpdateRequestBuilder withMedicalDiets(String... diets) {
      this.medicalDiets = new ArrayList<>(List.of(diets));
      return this;
    }

    public UpdateRequestBuilder withIntolerances(HardIntoleranceDto... intolerances) {
      this.intolerances = new ArrayList<>(List.of(intolerances));
      return this;
    }

    public UpdateRequestBuilder withAgeRestrictions(AgeRestrictionDto... restrictions) {
      this.ageRestrictions = new ArrayList<>(List.of(restrictions));
      return this;
    }

    public UpdateRequestBuilder withExpectedVersion(long expectedVersion) {
      this.expectedVersion = expectedVersion;
      return this;
    }

    /**
     * Set the GAP-04 confirmation flag that lets a Tier-1 removal proceed (default: unset/false).
     */
    public UpdateRequestBuilder withConfirmTier1Removals(boolean confirm) {
      this.confirmTier1Removals = confirm;
      return this;
    }

    public UpdateHardConstraintsRequest build() {
      return new UpdateHardConstraintsRequest(
          allergies,
          dietaryIdentity,
          medicalDiets,
          intolerances,
          ageRestrictions,
          expectedVersion,
          confirmTier1Removals);
    }
  }
}
