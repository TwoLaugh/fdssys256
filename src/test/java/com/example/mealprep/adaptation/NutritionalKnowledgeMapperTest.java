package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.api.dto.AbsorptionConflictDto;
import com.example.mealprep.adaptation.api.dto.MethodBioavailabilityDto;
import com.example.mealprep.adaptation.api.dto.NutritionalPairingDto;
import com.example.mealprep.adaptation.api.dto.PrepRequirementDto;
import com.example.mealprep.adaptation.api.mapper.NutritionalKnowledgeMapper;
import com.example.mealprep.adaptation.domain.entity.NutritionalKnowledgeEntry;
import com.example.mealprep.adaptation.domain.enums.KnowledgeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;

/**
 * Focused unit test for the {@link NutritionalKnowledgeMapper} {@code default}-method logic. Uses
 * the real generated mapper (no mocks) and targets the defensive branches PIT flagged as uncovered:
 * null entity, null/sparse payload, empty subject keys, and the {@code confidenceTier -> scalar}
 * fallback switch.
 */
class NutritionalKnowledgeMapperTest {

  private final NutritionalKnowledgeMapper mapper =
      Mappers.getMapper(NutritionalKnowledgeMapper.class);
  private final ObjectMapper json = new ObjectMapper();

  private NutritionalKnowledgeEntry entry(KnowledgeKind kind, String[] keys, String payloadJson)
      throws Exception {
    return NutritionalKnowledgeEntry.builder()
        .id(UUID.randomUUID())
        .knowledgeKind(kind)
        .subjectKeys(keys)
        .payload(payloadJson == null ? null : json.readTree(payloadJson))
        .confidenceTier("HIGH")
        .source("manual")
        .createdAt(Instant.now())
        .build();
  }

  // ---- null-entity guards ----

  @Test
  void all_mappers_return_null_for_null_entity() {
    assertThat(mapper.toPairingDto(null)).isNull();
    assertThat(mapper.toMethodEffectDto(null)).isNull();
    assertThat(mapper.toPrepRequirementDto(null)).isNull();
    assertThat(mapper.toConflictDto(null)).isNull();
  }

  @Test
  void toSubjectKeysList_handles_null_array() {
    assertThat(mapper.toSubjectKeysList(null)).isEmpty();
    assertThat(mapper.toSubjectKeysList(new String[] {"a", "b"})).containsExactly("a", "b");
  }

  // ---- toMethodEffectDto: empty subject-keys -> "" subjectKey ----

  @Test
  void toMethodEffectDto_empty_subject_keys_yields_blank_subject_key() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(KnowledgeKind.METHOD_BIOAVAILABILITY, new String[] {}, "{\"method\":\"BOIL\"}");
    MethodBioavailabilityDto dto = mapper.toMethodEffectDto(e);
    assertThat(dto.subjectKey()).isEmpty();
    assertThat(dto.method()).isEqualTo("BOIL");
  }

  @Test
  void toMethodEffectDto_null_subject_keys_yields_blank_subject_key() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(KnowledgeKind.METHOD_BIOAVAILABILITY, null, "{\"method\":\"BOIL\"}");
    assertThat(mapper.toMethodEffectDto(e).subjectKey()).isEmpty();
  }

  @Test
  void toMethodEffectDto_first_key_used_when_present() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(
            KnowledgeKind.METHOD_BIOAVAILABILITY,
            new String[] {"spinach", "kale"},
            "{\"method\":\"SAUTE\",\"effect\":\"+iron\",\"magnitude\":1.1}");
    MethodBioavailabilityDto dto = mapper.toMethodEffectDto(e);
    assertThat(dto.subjectKey()).isEqualTo("spinach");
    assertThat(dto.magnitude()).isEqualByComparingTo("1.1");
  }

  // ---- sparse / null payload defensive reads ----

  @Test
  void toMethodEffectDto_null_payload_yields_empty_strings_and_tier_independent_zero()
      throws Exception {
    NutritionalKnowledgeEntry e =
        entry(KnowledgeKind.METHOD_BIOAVAILABILITY, new String[] {"x"}, null);
    MethodBioavailabilityDto dto = mapper.toMethodEffectDto(e);
    assertThat(dto.method()).isEmpty();
    assertThat(dto.effect()).isEmpty();
    // magnitude uses null fallbackTier -> ZERO
    assertThat(dto.magnitude()).isEqualByComparingTo("0");
  }

  @Test
  void toPrepRequirementDto_missing_leadTime_yields_null_leadTime() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(KnowledgeKind.SOAK_NEEDED, new String[] {"bean"}, "{\"requirement\":\"soak\"}");
    PrepRequirementDto dto = mapper.toPrepRequirementDto(e);
    assertThat(dto.requirement()).isEqualTo("soak");
    assertThat(dto.leadTimeHours()).isNull();
  }

  @Test
  void toPrepRequirementDto_present_leadTime_is_read() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(
            KnowledgeKind.SOAK_NEEDED,
            new String[] {"bean"},
            "{\"requirement\":\"soak\",\"leadTimeHours\":12}");
    assertThat(mapper.toPrepRequirementDto(e).leadTimeHours()).isEqualTo(12);
  }

  @Test
  void toConflictDto_uses_payload_severity_when_present() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(
            KnowledgeKind.ABSORPTION_CONFLICT,
            new String[] {"a", "b"},
            "{\"conflict\":\"x blocks y\",\"severity\":\"CRITICAL\"}");
    AbsorptionConflictDto dto = mapper.toConflictDto(e);
    assertThat(dto.conflict()).isEqualTo("x blocks y");
    assertThat(dto.severity()).isEqualTo("CRITICAL");
  }

  @Test
  void toConflictDto_falls_back_to_confidence_tier_when_severity_absent() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(KnowledgeKind.ABSORPTION_CONFLICT, new String[] {"a"}, "{\"conflict\":\"z\"}");
    // payload has no "severity" -> falls back to confidenceTier ("HIGH")
    assertThat(mapper.toConflictDto(e).severity()).isEqualTo("HIGH");
  }

  // ---- decimalOrZero tier->scalar fallback switch ----

  @ParameterizedTest
  @CsvSource({"HIGH,0.90", "MEDIUM,0.60", "LOW,0.30", "GARBAGE,0", "high,0.90"})
  void pairing_confidence_falls_back_to_tier_scalar_when_payload_has_no_confidence(
      String tier, String expected) throws Exception {
    NutritionalKnowledgeEntry e =
        NutritionalKnowledgeEntry.builder()
            .id(UUID.randomUUID())
            .knowledgeKind(KnowledgeKind.PAIRING)
            .subjectKeys(new String[] {"iron"})
            .payload(json.readTree("{\"description\":\"d\"}"))
            .confidenceTier(tier)
            .source("manual")
            .createdAt(Instant.now())
            .build();
    NutritionalPairingDto dto = mapper.toPairingDto(e);
    assertThat(dto.confidence()).isEqualByComparingTo(expected);
  }

  @Test
  void pairing_confidence_prefers_explicit_payload_value_over_tier() throws Exception {
    NutritionalKnowledgeEntry e =
        entry(
            KnowledgeKind.PAIRING,
            new String[] {"iron"},
            "{\"description\":\"d\",\"confidence\":0.42}");
    // confidenceTier is HIGH (0.90) but the explicit payload value must win
    assertThat(mapper.toPairingDto(e).confidence()).isEqualByComparingTo("0.42");
  }
}
