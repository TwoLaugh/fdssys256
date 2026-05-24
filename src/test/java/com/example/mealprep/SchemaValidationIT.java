package com.example.mealprep;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.testsupport.TestContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Permanent CI gate for whole-schema-vs-entity drift.
 *
 * <p>Boots the FULL Spring context against a real Flyway-migrated Postgres (Testcontainers) with
 * {@code spring.jpa.hibernate.ddl-auto=validate} — overriding the test profile's {@code none}
 * default. This reproduces the exact startup path of the {@code prod}, {@code dev} and {@code e2e}
 * profiles, where Hibernate validates every {@code @Entity} mapping against the migrated columns at
 * boot. If ANY entity column's JDBC type disagrees with what Postgres reports, Hibernate throws
 * {@code SchemaManagementException} and the context fails to start — failing this test.
 *
 * <p>Why this exists: every other IT runs under {@code ddl-auto=none} (Flyway owns the schema and
 * Hibernate does no validation), so schema/entity drift was invisible until the prod-parity e2e
 * stack hit it. The original break was hypersistence-utils array types ({@code StringArrayType} /
 * {@code ListArrayType} / {@code UUIDArrayType}) reporting JDBC type {@code OTHER} for {@code
 * text[]} / {@code uuid[]} columns that Postgres reports as {@code Types#ARRAY} — rejected by the
 * Hibernate 6 validator. Fixed by switching those columns to Hibernate's native
 * {@code @JdbcTypeCode(SqlTypes.ARRAY)} mapping. This test makes the whole-schema validation a
 * permanent gate so that class of bug can never again hide behind {@code ddl-auto=none}.
 *
 * <p>Lives in the normal test source tree (not the {@code -Pe2e} source set) so a plain {@code mvn
 * verify} runs it.
 */
@SpringBootTest
@Import(TestContainersConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class SchemaValidationIT {

  @Autowired private ApplicationContext applicationContext;

  /**
   * If the context refreshed at all, Hibernate's {@code validate} pass succeeded for every entity
   * against the Flyway-migrated schema — the assertion is a formality; the gate is the successful
   * context bootstrap itself.
   */
  @Test
  void contextBootsUnderHibernateValidate() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBeanDefinitionCount()).isPositive();
  }
}
