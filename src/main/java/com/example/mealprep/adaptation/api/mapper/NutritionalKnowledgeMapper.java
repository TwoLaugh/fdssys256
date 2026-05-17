package com.example.mealprep.adaptation.api.mapper;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Named;

/**
 * Maps a {@link NutritionalKnowledgeEntry} reference-data row to one of the four typed food-science
 * DTOs. The structured detail lives in the entity's {@code payload} JSONB; this mapper lifts the
 * common scalar fields out of that payload while keeping the raw node available on each DTO's
 * {@code payload} carrier (per the 01b "JsonNode carrier" decision on {@link
 * NutritionalPairingDto}).
 *
 * <p>Per ticket 01e §Mapper (step 14); LLD §NutritionalKnowledgeService lines 543-573. The {@code
 * subject_keys} Postgres {@code text[]} maps to {@code List<String>} via the {@link
 * #toSubjectKeysList(String[])} {@code @Named} qualifier.
 *
 * <p>All four mappers are {@code default} methods (no MapStruct field-by-field generation): the
 * payload JSON shape is curated by the prompt-engineering seed work and read defensively here so a
 * sparse / partial payload never throws.
 */
@Mapper(componentModel = "spring")
public interface NutritionalKnowledgeMapper {

  /** Converts the Postgres {@code text[]} subject-keys column to an immutable {@code List}. */
  @Named("toSubjectKeysList")
  default List<String> toSubjectKeysList(String[] subjectKeys) {
    return subjectKeys == null ? List.of() : List.copyOf(Arrays.asList(subjectKeys));
  }

  /** Maps a {@code PAIRING} row to a {@link NutritionalPairingDto}. */
  default NutritionalPairingDto toPairingDto(NutritionalKnowledgeEntry entity) {
    if (entity == null) {
      return null;
    }
    JsonNode payload = entity.getPayload();
    return new NutritionalPairingDto(
        toSubjectKeysList(entity.getSubjectKeys()),
        textOrEmpty(payload, "description"),
        decimalOrZero(payload, "confidence", entity.getConfidenceTier()),
        payload);
  }

  /** Maps a {@code METHOD_BIOAVAILABILITY} row to a {@link MethodBioavailabilityDto}. */
  default MethodBioavailabilityDto toMethodEffectDto(NutritionalKnowledgeEntry entity) {
    if (entity == null) {
      return null;
    }
    JsonNode payload = entity.getPayload();
    String[] keys = entity.getSubjectKeys();
    return new MethodBioavailabilityDto(
        keys == null || keys.length == 0 ? "" : keys[0],
        textOrEmpty(payload, "method"),
        textOrEmpty(payload, "effect"),
        decimalOrZero(payload, "magnitude", null),
        payload);
  }

  /** Maps a {@code SOAK_NEEDED} row to a {@link PrepRequirementDto}. */
  default PrepRequirementDto toPrepRequirementDto(NutritionalKnowledgeEntry entity) {
    if (entity == null) {
      return null;
    }
    JsonNode payload = entity.getPayload();
    Integer leadTime =
        payload != null && payload.hasNonNull("leadTimeHours")
            ? payload.get("leadTimeHours").asInt()
            : null;
    return new PrepRequirementDto(
        toSubjectKeysList(entity.getSubjectKeys()),
        textOrEmpty(payload, "requirement"),
        leadTime,
        payload);
  }

  /** Maps an {@code ABSORPTION_CONFLICT} row to an {@link AbsorptionConflictDto}. */
  default AbsorptionConflictDto toConflictDto(NutritionalKnowledgeEntry entity) {
    if (entity == null) {
      return null;
    }
    JsonNode payload = entity.getPayload();
    return new AbsorptionConflictDto(
        toSubjectKeysList(entity.getSubjectKeys()),
        textOrEmpty(payload, "conflict"),
        payload != null && payload.hasNonNull("severity")
            ? payload.get("severity").asText()
            : entity.getConfidenceTier(),
        payload);
  }

  private static String textOrEmpty(JsonNode payload, String field) {
    return payload != null && payload.hasNonNull(field) ? payload.get(field).asText() : "";
  }

  private static BigDecimal decimalOrZero(JsonNode payload, String field, String fallbackTier) {
    if (payload != null && payload.hasNonNull(field)) {
      return payload.get(field).decimalValue();
    }
    // No numeric confidence in the payload — fall back to a coarse tier→scalar mapping.
    if (fallbackTier == null) {
      return BigDecimal.ZERO;
    }
    return switch (fallbackTier.toUpperCase()) {
      case "HIGH" -> new BigDecimal("0.90");
      case "MEDIUM" -> new BigDecimal("0.60");
      case "LOW" -> new BigDecimal("0.30");
      default -> BigDecimal.ZERO;
    };
  }
}
