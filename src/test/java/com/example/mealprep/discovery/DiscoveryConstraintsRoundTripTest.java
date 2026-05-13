package com.example.mealprep.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.dto.DiscoveryConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip discipline for the {@link DiscoveryConstraints} JSONB document per style-guide §JSONB
 * §Required discipline. Serialise → JsonNode → record; assert field-for-field equality so a silent
 * field drift in the record (a rename, a removed field, a missing {@code @JsonProperty}) breaks
 * here at unit-test time rather than at runtime when 01b's service tries to materialise a persisted
 * job.
 */
class DiscoveryConstraintsRoundTripTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void roundTrip_populatedConstraints_preservesAllFields() throws Exception {
    DiscoveryConstraints original =
        new DiscoveryConstraints(
            1,
            List.of("East Asian", "Mediterranean"),
            List.of("dinner", "lunch"),
            45,
            List.of("peanuts", "shellfish"),
            List.of("vegetarian", "gluten_free"),
            List.of("lighter dishes", "high-protein"),
            20);

    JsonNode tree = objectMapper.valueToTree(original);
    DiscoveryConstraints roundTripped = objectMapper.treeToValue(tree, DiscoveryConstraints.class);

    assertThat(roundTripped).isEqualTo(original);
    assertThat(roundTripped.schemaVersion()).isEqualTo(1);
    assertThat(roundTripped.requiredCuisines()).containsExactly("East Asian", "Mediterranean");
    assertThat(roundTripped.dietaryFlags()).containsExactly("vegetarian", "gluten_free");
    assertThat(roundTripped.maxRecipesPerSource()).isEqualTo(20);
  }

  @Test
  void roundTrip_constraintsWithNullableFieldsAbsent_preservesNulls() throws Exception {
    DiscoveryConstraints original =
        new DiscoveryConstraints(
            1, List.of(), List.of(), null, List.of(), List.of(), List.of(), null);

    JsonNode tree = objectMapper.valueToTree(original);
    DiscoveryConstraints roundTripped = objectMapper.treeToValue(tree, DiscoveryConstraints.class);

    assertThat(roundTripped).isEqualTo(original);
    assertThat(roundTripped.maxTotalTimeMins()).isNull();
    assertThat(roundTripped.maxRecipesPerSource()).isNull();
  }

  @Test
  void roundTrip_serialisedStringContainsSchemaVersion() throws Exception {
    DiscoveryConstraints original =
        new DiscoveryConstraints(
            1, List.of(), List.of(), null, List.of(), List.of(), List.of(), null);
    String json = objectMapper.writeValueAsString(original);
    assertThat(json).contains("\"schemaVersion\":1");
  }
}
