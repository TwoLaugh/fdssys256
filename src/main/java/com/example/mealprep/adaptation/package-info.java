/**
 * Adaptation pipeline module. Owns the culinary-intelligence half of the Recipe System per {@code
 * design/recipe-system.md}: the four trigger flows (import, feedback, data-model change, plan-time
 * refine-directive), the candidate -> rollup -> LLM-pick pipeline, pending-change shepherding,
 * planner-hint emission, and the {@code NutritionalKnowledgeService} interface.
 *
 * <p>01a ships the JPA + Flyway + repository skeleton only. Services / controllers / events land in
 * 01b through 01f. See {@code lld/adaptation-pipeline.md} for the full module spec.
 */
package com.example.mealprep.adaptation;
