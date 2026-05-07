package com.example.mealprep.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Optional explicit Testcontainers bootstrap.
 *
 * <p>Most ITs simply rely on the {@code jdbc:tc:postgresql:16-alpine:///mealprep} URL convention
 * configured in {@code application-test.properties}, which spins up a container per JVM. This
 * configuration is the alternative path for tests that need explicit access to the container (for
 * example to grab the Postgres host/port programmatically) via Spring Boot 3.1+'s
 * {@code @ServiceConnection}.
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
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("mealprep")
        .withUsername("test")
        .withPassword("test")
        .withReuse(true);
  }
}
