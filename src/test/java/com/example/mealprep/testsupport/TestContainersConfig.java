package com.example.mealprep.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Canonical Testcontainers bootstrap for ITs that need a real Postgres.
 *
 * <p>Uses the {@code pgvector/pgvector:pg16} image (Postgres 16 with the {@code vector} extension
 * pre-installed at the OS level) — the {@code V…__core_install_pgvector.sql} migration calls {@code
 * CREATE EXTENSION vector}, which fails on the plain {@code postgres:16-alpine} image.
 *
 * <p>{@code asCompatibleSubstituteFor("postgres")} tells Testcontainers to treat the image as a
 * drop-in replacement for the upstream postgres image.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestContainersConfig.class)
 * class MyControllerIT { ... }
 * }</pre>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestContainersConfig {

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("mealprep")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);
  }
}
