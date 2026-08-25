package com.example.mealprep.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.mealprep.nutrition.validation.MicrosDocumentValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintValidatorContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link MicrosDocumentValidator}: the request-side gate matching the contract's
 * {@code additionalProperties: number, minimum: 0} on micros documents.
 */
class MicrosDocumentValidatorTest {

  private static final ObjectMapper OM = new ObjectMapper();

  private final MicrosDocumentValidator validator = new MicrosDocumentValidator();

  private ConstraintValidatorContext context() {
    ConstraintValidatorContext ctx = mock(ConstraintValidatorContext.class);
    ConstraintValidatorContext.ConstraintViolationBuilder builder =
        mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
    when(ctx.buildConstraintViolationWithTemplate(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(builder);
    return ctx;
  }

  @Test
  void nullAndJsonNull_pass() {
    assertThat(validator.isValid(null, context())).isTrue();
    assertThat(validator.isValid(NullNode.getInstance(), context())).isTrue();
  }

  @Test
  void objectOfNonNegativeNumbers_passes_zeroIncluded() {
    ObjectNode node = OM.createObjectNode();
    node.put("iron_mg", new BigDecimal("4.5"));
    node.put("zinc_mg", BigDecimal.ZERO); // a measured zero is legal and meaningful
    assertThat(validator.isValid(node, context())).isTrue();
  }

  @Test
  void negativeValue_fails() {
    ObjectNode node = OM.createObjectNode();
    node.put("iron_mg", new BigDecimal("-0.1"));
    assertThat(validator.isValid(node, context())).isFalse();
  }

  @Test
  void nonNumericValue_fails() {
    ObjectNode node = OM.createObjectNode();
    node.put("iron_mg", "lots");
    assertThat(validator.isValid(node, context())).isFalse();
  }

  @Test
  void nonObjectNode_fails() {
    assertThat(validator.isValid(OM.createArrayNode(), context())).isFalse();
    assertThat(validator.isValid(new ObjectMapper().getNodeFactory().numberNode(3), context()))
        .isFalse();
  }

  @Test
  void emptyObject_passes() {
    assertThat(validator.isValid(OM.createObjectNode(), context())).isTrue();
  }
}
