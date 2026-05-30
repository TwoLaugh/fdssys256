/**
 * Public service interfaces for the adaptation pipeline module: {@code AdaptationService} (write
 * surface — four trigger entries + pending-change lifecycle + sweeps), {@code
 * AdaptationQueryService} (read fan-out), and {@code NutritionalKnowledgeService}. A single {@code
 * AdaptationServiceImpl} implements the first two; every method is fully implemented (the
 * ticket-by-ticket {@code UnsupportedOperationException} skeleton was historically filled across
 * 01c worker pipeline, 01d trigger entries + pending-change lifecycle, 01e context assembly, and
 * 01f planner-hint / fingerprint / read fan-out).
 *
 * <p>Internal helpers live under {@code .internal/} and are package-private: candidate generation
 * ({@code CandidateGenerator} + the four strategies), scoring ({@code ScoringEngine}), LLM dispatch
 * ({@code AdaptationLlmInvoker}), validation gates, the apply seam ({@code RebaseOrchestrator},
 * {@code PendingChangeStore}, {@code FingerprintRefresher}), batch/orphan orchestration, and the
 * noop nutritional-knowledge service.
 */
package com.example.mealprep.adaptation.domain.service;
