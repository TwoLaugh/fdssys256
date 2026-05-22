package com.example.mealprep.core.origin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker placed on a controller class or controller method declaring "this endpoint accepts
 * non-USER origin". Absence is the default and means user-only — the {@link OriginFilter} rejects
 * non-USER {@code X-Origin} headers on unannotated handlers with HTTP 403.
 *
 * <p>This is defence-in-depth: it ensures a future contributor adding a brand-new mutation endpoint
 * cannot accidentally accept system-driven traffic without opting in. Per {@code
 * design/origin-tracking-pattern.md} §Implementation outline (3).
 *
 * <p>No controllers are annotated in {@code core-02b}; per-consumer tickets ({@code feedback-01g},
 * adaptation, scheduled jobs) opt their endpoints in.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OriginAware {}
