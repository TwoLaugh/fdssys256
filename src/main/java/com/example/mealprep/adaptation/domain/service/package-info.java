/**
 * Placeholder package for the adaptation module's public service interfaces. 01a deliberately does
 * NOT ship interfaces here — the contract-lock work is 01b, which is the dependency the parallel
 * planner / feedback sibling agents will wait on. Once 01b lands this package will hold {@code
 * AdaptationService}, {@code AdaptationQueryService}, and {@code NutritionalKnowledgeService} plus
 * the {@code AdaptationServiceImpl}; internal helpers live under {@code .internal/}.
 */
package com.example.mealprep.adaptation.domain.service;
