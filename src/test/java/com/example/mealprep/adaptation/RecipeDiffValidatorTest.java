package com.example.mealprep.adaptation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.adaptation.validation.RecipeDiffValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** Unit tests for the structural {@code RecipeDiffValidator}. */
class RecipeDiffValidatorTest {

  private final RecipeDiffValidator v = new RecipeDiffValidator();

  @Test
  void null_is_valid() {
    assertThat(v.isValid(null, null)).isTrue();
  }

  @Test
  void empty_object_is_valid() {
    assertThat(v.isValid(JsonNodeFactory.instance.objectNode(), null)).isTrue();
  }

  @Test
  void non_object_top_level_fails() {
    JsonNode arr = JsonNodeFactory.instance.arrayNode();
    assertThat(v.isValid(arr, null)).isFalse();
  }

  @Test
  void blank_base_version_id_fails() {
    ObjectNode obj = JsonNodeFactory.instance.objectNode();
    obj.put("base_version_id", "");
    assertThat(v.isValid(obj, null)).isFalse();
  }

  @Test
  void valid_base_version_id_passes() {
    ObjectNode obj = JsonNodeFactory.instance.objectNode();
    obj.put("base_version_id", "11111111-2222-3333-4444-555555555555");
    assertThat(v.isValid(obj, null)).isTrue();
  }

  @Test
  void blank_ingredient_mapping_key_fails() {
    ObjectNode obj = JsonNodeFactory.instance.objectNode();
    var arr = JsonNodeFactory.instance.arrayNode();
    var ing = JsonNodeFactory.instance.objectNode();
    ing.put("mapping_key", "");
    arr.add(ing);
    obj.set("ingredients", arr);
    assertThat(v.isValid(obj, null)).isFalse();
  }
}
