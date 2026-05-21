package com.example.mealprep.core.api.markers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method that legitimately returns an unpaginated {@code List<*Dto>} or {@code
 * Collection<*Dto>}. Use ONLY when the cardinality is bounded by domain semantics (e.g. a recipe's
 * branches are typically &lt; 10; a decision-log trace is bounded by the request that produced it).
 *
 * <p>The {@code ArchUnit} rule {@code listReturningControllersMustBeAnnotatedBoundedCollection}
 * fails the build for any new {@code GetMapping} method that returns {@code List<*Dto>} / {@code
 * Collection<*Dto>} without this annotation. The intent is to make the default choice for new
 * endpoints to be {@code Page<>} + {@code Pageable}, and to require explicit justification (via
 * this annotation) when a list is legitimate.
 *
 * <p>Per ticket {@code infra/01b-list-endpoint-pagination-audit}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BoundedCollection {
  /**
   * Free-form justification for why an unpaginated list is acceptable here. Surfaces in code review
   * as "did the author actually think about bounding?".
   */
  String value() default "";
}
