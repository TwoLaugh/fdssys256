package com.example.mealprep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.notification.domain.entity.NotificationKind;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the contract against enum drift: every Java {@link NotificationKind} must appear in the
 * OpenAPI {@code NotificationKind} enum and vice versa. The swagger-request-validator only
 * exercises kinds the tests happen to emit, so two shipped values ({@code
 * STAPLE_REPLENISHMENT_NEEDED}, {@code FEEDBACK_CONFIRMATION}) drifted out of the spec and would
 * have broken generated frontend types at runtime (tickets/frontend-gaps/
 * notification-kind-enum.md). This test makes the drift a compile-adjacent failure instead.
 */
class NotificationKindContractParityTest {

  @Test
  @SuppressWarnings("unchecked")
  void javaEnumAndOpenApiEnumAreIdentical() throws Exception {
    Map<String, Object> schemas;
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("openapi/schemas/notification.yaml")) {
      assertThat(in).as("openapi/schemas/notification.yaml on classpath").isNotNull();
      schemas = new Yaml().load(in);
    }

    Map<String, Object> kindSchema = (Map<String, Object>) schemas.get("NotificationKind");
    assertThat(kindSchema).as("NotificationKind schema present").isNotNull();
    List<String> yamlValues = (List<String>) kindSchema.get("enum");

    Set<String> javaValues =
        Arrays.stream(NotificationKind.values()).map(Enum::name).collect(Collectors.toSet());

    assertThat(yamlValues)
        .as("OpenAPI NotificationKind enum must list exactly the Java enum values")
        .containsExactlyInAnyOrderElementsOf(javaValues);
  }
}
