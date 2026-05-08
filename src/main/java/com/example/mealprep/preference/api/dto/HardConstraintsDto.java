package com.example.mealprep.preference.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Read shape of a user's hard-constraints aggregate. {@code version} is the JPA {@code @Version}
 * value the client must echo back as {@code expectedVersion} on a subsequent PUT.
 */
public record HardConstraintsDto(
    UUID id,
    UUID userId,
    List<String> allergies,
    DietaryIdentityDto dietaryIdentity,
    List<String> medicalDiets,
    List<HardIntoleranceDto> intolerances,
    List<AgeRestrictionDto> ageRestrictions,
    long version) {}
