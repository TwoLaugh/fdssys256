package com.example.mealprep.adaptation;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import com.example.mealprep.adaptation.domain.service.AdaptationQueryService;
import com.example.mealprep.adaptation.domain.service.AdaptationService;
import com.example.mealprep.adaptation.domain.service.NutritionalKnowledgeService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Module facade for the adaptation pipeline. Re-exports the three public service interfaces shipped
 * in 01b — sibling Wave-3 modules (planner, feedback, notification) inject this facade or the
 * individual services rather than reaching into {@code domain.service.*} directly.
 *
 * <p>{@code @EnableConfigurationProperties(AdaptationConfig.class)} preserves the
 * configuration-binding wired in 01a; the {@code @Component} annotation is added in 01b alongside
 * the service injections so the facade can be {@code @Autowired} into cross-module callers. The
 * {@code @Lazy} fields break any circular load order should a sibling module reference {@code
 * AdaptationModule} during its own startup. <b>Worth user review</b> — alternative is no facade
 * (sibling modules inject {@code AdaptationService} directly); the facade matches every other
 * module's pattern per LLD line 28.
 *
 * <p>{@link NutritionalKnowledgeService} is wired to the Noop default in 01b (see {@code
 * internal.NoopNutritionalKnowledgeConfiguration}); ticket-01e replaces with the real
 * implementation.
 */
@Component
@EnableConfigurationProperties(AdaptationConfig.class)
public class AdaptationModule {

  private final AdaptationService adaptationService;
  private final AdaptationQueryService adaptationQueryService;
  private final NutritionalKnowledgeService nutritionalKnowledgeService;

  public AdaptationModule(
      @Lazy AdaptationService adaptationService,
      @Lazy AdaptationQueryService adaptationQueryService,
      @Lazy NutritionalKnowledgeService nutritionalKnowledgeService) {
    this.adaptationService = adaptationService;
    this.adaptationQueryService = adaptationQueryService;
    this.nutritionalKnowledgeService = nutritionalKnowledgeService;
  }

  /** Write surface — Trigger 1/2/3/4 entries + pending-change lifecycle + planner hints. */
  public AdaptationService adaptationService() {
    return adaptationService;
  }

  /** Read fan-out — pending-change reads, job + trace history, planner-hint reads. */
  public AdaptationQueryService adaptationQueryService() {
    return adaptationQueryService;
  }

  /** Upgradeable food-science seam — Noop in 01b, real impl in 01e. */
  public NutritionalKnowledgeService nutritionalKnowledgeService() {
    return nutritionalKnowledgeService;
  }
}
