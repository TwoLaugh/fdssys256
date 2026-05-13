package com.example.mealprep.adaptation;

import com.example.mealprep.adaptation.config.AdaptationConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module facade for the adaptation pipeline. 01a is the schema + persistence skeleton, so the
 * facade currently only enables {@link AdaptationConfig} binding — it does not yet re-export any
 * public service interfaces. 01b adds {@code AdaptationService}, {@code AdaptationQueryService},
 * and {@code NutritionalKnowledgeService} re-exports per LLD §Service Interfaces (line 40).
 *
 * <p>The class is annotated {@code @Configuration} (rather than {@code @Component}) so the
 * {@code @EnableConfigurationProperties} import is picked up by Spring Boot's component scan
 * without needing a sibling {@code @SpringBootApplication}-level wire. Mirrors the lightweight
 * configuration-only shape; later facades for query / update / nutritional-knowledge services land
 * in 01b alongside their interfaces.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdaptationConfig.class)
public class AdaptationModule {}
