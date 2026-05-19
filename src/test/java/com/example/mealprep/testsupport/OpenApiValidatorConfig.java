package com.example.mealprep.testsupport;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires {@code swagger-request-validator} into MockMvc for contract testing.
 *
 * <p>{@link #openApiValidator()} bean is available for assertion-style validation via {@code
 * com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi()}; tests apply it inline:
 *
 * <pre>{@code
 * import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
 *
 * @WebMvcTest(MyController.class)
 * @Import(OpenApiValidatorConfig.class)
 * class MyControllerIT {
 *   @Autowired MockMvc mvc;
 *   @Autowired OpenApiInteractionValidator validator;
 *
 *   @Test void contractMatches() throws Exception {
 *     mvc.perform(get("/...")).andExpect(openApi().isValid(validator));
 *   }
 * }
 * }</pre>
 *
 * <p>The classpath location of the spec is read from {@code mealprep.openapi.spec-classpath}
 * (default {@code openapi/openapi.yaml}) so individual ITs can override the spec under test.
 *
 * <p>Note: the upstream {@code swagger-request-validator-springmvc} servlet filter still targets
 * {@code javax.servlet}; on Spring 6 / Jakarta we use the {@code mockmvc} matcher style instead and
 * skip the filter entirely.
 *
 * <p>The validator is loaded by URL (not inline content) so swagger-parser resolves external {@code
 * $ref}s in {@code paths/*.yaml} and {@code schemas/*.yaml} against the entry file's location.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OpenApiValidatorConfig {

  private final String specClasspath;

  public OpenApiValidatorConfig(
      @Value("${mealprep.openapi.spec-classpath:openapi/openapi.yaml}") String specClasspath) {
    this.specClasspath = specClasspath;
  }

  /**
   * Validator that ignores authentication checks. Tests focus on schema correctness; once {@code
   * auth-01} wires the filter chain, security can be tightened here.
   */
  @Bean
  @Primary
  public OpenApiInteractionValidator openApiValidator() {
    var url = getClass().getClassLoader().getResource(specClasspath);
    if (url == null) {
      throw new IllegalStateException("OpenAPI spec not found on classpath: " + specClasspath);
    }
    return OpenApiInteractionValidator.createFor(url.toString())
        .withLevelResolver(
            LevelResolver.create()
                .withLevel("validation.request.security.missing", ValidationReport.Level.IGNORE)
                .build())
        .build();
  }

  /**
   * Variant that ignores all request-side findings ({@code validation.request.*}) while still
   * enforcing the response contract.
   *
   * <p>Use this only for negative-path tests that deliberately send a malformed request to exercise
   * the API's 4xx handling (e.g. a blank {@code url} to trigger 400). Such a request can never be
   * contract-valid by construction, so asserting full {@code openApi().isValid()} against the
   * default validator would always fail; but the <em>response</em> the server produces (the RFC
   * 9457 ProblemDetail) still must conform, and that is what this validator continues to check.
   * Prefer the {@code @Primary} {@link #openApiValidator()} for every happy-path and
   * documented-error case.
   */
  @Bean
  public OpenApiInteractionValidator responseOnlyOpenApiValidator() {
    var url = getClass().getClassLoader().getResource(specClasspath);
    if (url == null) {
      throw new IllegalStateException("OpenAPI spec not found on classpath: " + specClasspath);
    }
    return OpenApiInteractionValidator.createFor(url.toString())
        .withLevelResolver(
            LevelResolver.create()
                .withLevel("validation.request", ValidationReport.Level.IGNORE)
                .build())
        .build();
  }
}
