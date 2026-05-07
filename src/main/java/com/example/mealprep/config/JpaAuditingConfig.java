package com.example.mealprep.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing globally.
 *
 * <p>With this in place, any entity may use {@code @CreatedDate} / {@code @LastModifiedDate} (and,
 * once an {@code AuditorAware} bean is registered, {@code @CreatedBy} / {@code @LastModifiedBy})
 * without per-class wiring. Per the style guide, every entity carries {@code createdAt} / {@code
 * updatedAt} columns that rely on this configuration.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
