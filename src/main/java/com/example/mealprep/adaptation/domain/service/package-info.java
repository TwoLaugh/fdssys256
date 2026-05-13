/**
 * Public service interfaces for the adaptation pipeline module. 01b ships {@code
 * AdaptationService}, {@code AdaptationQueryService}, and {@code NutritionalKnowledgeService} plus
 * a single {@code AdaptationServiceImpl} skeleton (every body throws {@code
 * UnsupportedOperationException} naming the ticket that fills it in — 01c/01d/01e/01f).
 *
 * <p>Internal helpers live under {@code .internal/} and are package-private (the noop
 * nutritional-knowledge service is the only 01b inhabitant; 01c-01f add candidate / scoring /
 * rebase / pending-store / fingerprint-refresher helpers).
 */
package com.example.mealprep.adaptation.domain.service;
