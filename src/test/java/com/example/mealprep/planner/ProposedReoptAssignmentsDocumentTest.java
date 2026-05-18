package com.example.mealprep.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument;
import com.example.mealprep.planner.api.dto.ProposedReoptAssignmentsDocument.ProposedSlotChange;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * JSONB round-trip guard for {@link ProposedReoptAssignmentsDocument} per {@code style-guide.md
 * §JSONB} ("a unit test that loads a fixture document into the record and back to JSON, confirming
 * the shape is preserved"). Catches silent field drift on the persisted re-opt diff.
 */
class ProposedReoptAssignmentsDocumentTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void roundTrips_throughJackson_preservingEveryField() throws Exception {
    ProposedSlotChange withOld =
        new ProposedSlotChange(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            4,
            "swapped for a cheaper recipe");
    ProposedSlotChange nullOld =
        new ProposedSlotChange(
            UUID.randomUUID(),
            null, // original slot had no recipe (eating-out slot now filled)
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            "filled an empty slot");
    ProposedReoptAssignmentsDocument doc =
        ProposedReoptAssignmentsDocument.of(List.of(withOld, nullOld));

    String json = mapper.writeValueAsString(doc);
    ProposedReoptAssignmentsDocument back =
        mapper.readValue(json, ProposedReoptAssignmentsDocument.class);

    assertThat(back).isEqualTo(doc);
    assertThat(back.schemaVersion()).isEqualTo(1);
    assertThat(back.changes()).hasSize(2);
    assertThat(back.changes().get(1).oldRecipeId()).isNull();
  }

  @Test
  void of_stampsCurrentSchemaVersion_andNullChangesNormalisesToEmpty() {
    assertThat(ProposedReoptAssignmentsDocument.of(List.of()).schemaVersion())
        .isEqualTo(ProposedReoptAssignmentsDocument.CURRENT_SCHEMA_VERSION);
    assertThat(new ProposedReoptAssignmentsDocument(1, null).changes()).isEmpty();
  }
}
