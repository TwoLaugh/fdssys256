package com.example.mealprep.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.recipe.api.dto.ChangeAction;
import com.example.mealprep.recipe.api.dto.RecipeDiffDto;
import com.example.mealprep.recipe.api.mapper.RecipeDiffMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage of {@link RecipeDiffMapper}'s JSONB → DTO projection: null root, non-array
 * sections, the invalid-enum {@code parseAction} fallback, null-node coalescing, and the {@code
 * fieldChanged}/{@code from}/{@code to} presence branches. Real Jackson nodes, no mocking.
 */
class RecipeDiffMapperTest {

  private final RecipeDiffMapper mapper = new RecipeDiffMapper();
  private final ObjectMapper om = new ObjectMapper();
  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private final UUID from = UUID.randomUUID();
  private final UUID to = UUID.randomUUID();

  @Test
  void nullChangeDiff_yieldsAllEmptySections_butPreservesIds() {
    RecipeDiffDto dto = mapper.fromJsonNode(from, to, null);

    assertThat(dto.fromVersionId()).isEqualTo(from);
    assertThat(dto.toVersionId()).isEqualTo(to);
    assertThat(dto.ingredientChanges()).isEmpty();
    assertThat(dto.methodChanges()).isEmpty();
    assertThat(dto.metadataChanges()).isEmpty();
    assertThat(dto.tagChanges()).isEmpty();
  }

  @Test
  void nonArraySections_yieldEmptyLists() {
    ObjectNode root = F.objectNode();
    root.put("ingredientChanges", "not-an-array");
    root.put("methodChanges", 42);
    root.set("metadataChanges", F.objectNode());
    // tagChanges absent entirely → path() returns MissingNode (not array).

    RecipeDiffDto dto = mapper.fromJsonNode(from, to, root);

    assertThat(dto.ingredientChanges()).isEmpty();
    assertThat(dto.methodChanges()).isEmpty();
    assertThat(dto.metadataChanges()).isEmpty();
    assertThat(dto.tagChanges()).isEmpty();
  }

  @Test
  void ingredientChange_modified_withAllFields() throws Exception {
    ObjectNode entry = F.objectNode();
    entry.put("action", "MODIFIED");
    entry.set("from", F.textNode("old"));
    entry.set("to", F.textNode("new"));
    entry.put("fieldChanged", "quantity");

    RecipeDiffDto dto = mapper.fromJsonNode(from, to, wrap("ingredientChanges", entry));

    assertThat(dto.ingredientChanges()).hasSize(1);
    var c = dto.ingredientChanges().get(0);
    assertThat(c.action()).isEqualTo(ChangeAction.MODIFIED);
    assertThat(c.from().asText()).isEqualTo("old");
    assertThat(c.to().asText()).isEqualTo("new");
    assertThat(c.fieldChanged()).isEqualTo("quantity");
    assertThat(om).isNotNull();
  }

  @Test
  void ingredientChange_invalidActionString_parsesToNull() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "NOT_A_REAL_ACTION");
    entry.put("fieldChanged", "displayName");

    var c =
        mapper.fromJsonNode(from, to, wrap("ingredientChanges", entry)).ingredientChanges().get(0);

    assertThat(c.action()).isNull();
    assertThat(c.fieldChanged()).isEqualTo("displayName");
  }

  @Test
  void ingredientChange_missingAction_parsesToNull() {
    ObjectNode entry = F.objectNode();
    entry.put("fieldChanged", "unit");

    var c =
        mapper.fromJsonNode(from, to, wrap("ingredientChanges", entry)).ingredientChanges().get(0);

    assertThat(c.action()).isNull();
  }

  @Test
  void ingredientChange_nullFromTo_andMissingFieldChanged_areNull() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "ADDED");
    entry.set("from", F.nullNode());
    // "to" absent entirely; "fieldChanged" absent entirely.

    var c =
        mapper.fromJsonNode(from, to, wrap("ingredientChanges", entry)).ingredientChanges().get(0);

    assertThat(c.action()).isEqualTo(ChangeAction.ADDED);
    assertThat(c.from()).isNull();
    assertThat(c.to()).isNull();
    assertThat(c.fieldChanged()).isNull();
  }

  @Test
  void ingredientChange_explicitNullFieldChanged_isNull() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "REMOVED");
    entry.set("fieldChanged", F.nullNode());

    var c =
        mapper.fromJsonNode(from, to, wrap("ingredientChanges", entry)).ingredientChanges().get(0);

    assertThat(c.fieldChanged()).isNull();
  }

  @Test
  void methodChange_readsStepAndFromTo() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "MODIFIED");
    entry.put("step", 3);
    entry.set("from", F.textNode("Stir"));
    entry.set("to", F.textNode("Whisk"));

    var c = mapper.fromJsonNode(from, to, wrap("methodChanges", entry)).methodChanges().get(0);

    assertThat(c.action()).isEqualTo(ChangeAction.MODIFIED);
    assertThat(c.step()).isEqualTo(3);
    assertThat(c.from()).isEqualTo("Stir");
    assertThat(c.to()).isEqualTo("Whisk");
  }

  @Test
  void methodChange_missingStepDefaultsToZero_nullFromToAreNull() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "ADDED");
    entry.set("from", F.nullNode());
    // step + to absent.

    var c = mapper.fromJsonNode(from, to, wrap("methodChanges", entry)).methodChanges().get(0);

    assertThat(c.step()).isZero();
    assertThat(c.from()).isNull();
    assertThat(c.to()).isNull();
  }

  @Test
  void metadataChange_readsFieldAndNodes() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "MODIFIED");
    entry.put("field", "servings");
    entry.set("from", F.numberNode(2));
    entry.set("to", F.numberNode(4));

    var c = mapper.fromJsonNode(from, to, wrap("metadataChanges", entry)).metadataChanges().get(0);

    assertThat(c.action()).isEqualTo(ChangeAction.MODIFIED);
    assertThat(c.field()).isEqualTo("servings");
    assertThat(c.from().asInt()).isEqualTo(2);
    assertThat(c.to().asInt()).isEqualTo(4);
  }

  @Test
  void metadataChange_missingFieldIsNull_missingNodesAreNull() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "ADDED");

    var c = mapper.fromJsonNode(from, to, wrap("metadataChanges", entry)).metadataChanges().get(0);

    assertThat(c.field()).isNull();
    assertThat(c.from()).isNull();
    assertThat(c.to()).isNull();
  }

  @Test
  void tagChange_readsDimensionAndNodes() {
    ObjectNode entry = F.objectNode();
    entry.put("action", "REMOVED");
    entry.put("dimension", "flavourProfile");
    entry.set("from", F.textNode("spicy"));

    var c = mapper.fromJsonNode(from, to, wrap("tagChanges", entry)).tagChanges().get(0);

    assertThat(c.action()).isEqualTo(ChangeAction.REMOVED);
    assertThat(c.dimension()).isEqualTo("flavourProfile");
    assertThat(c.from().asText()).isEqualTo("spicy");
    assertThat(c.to()).isNull();
  }

  @Test
  void allFourSectionsPopulatedTogether() {
    ObjectNode root = F.objectNode();
    root.set("ingredientChanges", arr(actionEntry("ADDED")));
    root.set("methodChanges", arr(actionEntry("REMOVED")));
    root.set("metadataChanges", arr(actionEntry("MODIFIED")));
    root.set("tagChanges", arr(actionEntry("ADDED")));

    RecipeDiffDto dto = mapper.fromJsonNode(from, to, root);

    assertThat(dto.ingredientChanges()).hasSize(1);
    assertThat(dto.methodChanges()).hasSize(1);
    assertThat(dto.metadataChanges()).hasSize(1);
    assertThat(dto.tagChanges()).hasSize(1);
    assertThat(dto.methodChanges().get(0).action()).isEqualTo(ChangeAction.REMOVED);
  }

  // ---------------- helpers ----------------

  private static ObjectNode actionEntry(String action) {
    ObjectNode n = F.objectNode();
    n.put("action", action);
    return n;
  }

  private static ArrayNode arr(JsonNode entry) {
    ArrayNode a = F.arrayNode();
    a.add(entry);
    return a;
  }

  private static JsonNode wrap(String section, JsonNode entry) {
    ObjectNode root = F.objectNode();
    root.set(section, arr(entry));
    return root;
  }
}
