package com.example.mealprep.auth.config;

import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Replaces the permissive bootstrap chain. The new chain is deny-by-default with a small whitelist
 * for register / login / OpenAPI; everything else requires the {@link SessionAuthenticationFilter}
 * to have attached an authenticated principal.
 *
 * <p>CSRF is disabled because the cookie is {@code SameSite=Lax} and the JSON-only POSTs reject
 * {@code application/x-www-form-urlencoded} via the controller's content-type matching.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class AuthSecurityConfig {

  /** System default clock — overridden in tests via fixed clocks. */
  @Bean
  @ConditionalOnMissingBean
  public Clock systemClock() {
    return Clock.systemUTC();
  }

  @Bean
  public SessionAuthenticationFilter sessionAuthenticationFilter(
      SessionRepository sessionRepository,
      UserRepository userRepository,
      SessionTokenGenerator tokenGenerator,
      AuthProperties authProperties,
      Clock clock) {
    return new SessionAuthenticationFilter(
        sessionRepository, userRepository, tokenGenerator, authProperties, clock);
  }

  @Bean
  public SecurityFilterChain authSecurityFilterChain(
      HttpSecurity http, SessionAuthenticationFilter sessionFilter) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        // Spring Security still tracks an HttpSession by default; we don't use it. Stateless mode
        // ensures no JSESSIONID is created and the only auth state lives in the AUTH_SESSION
        // cookie + DB row.
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        // Logout is anonymous-accessible so the idempotent "second call" path
                        // (cookie still presented, session already revoked) reaches the controller
                        // and returns 204 + cleared cookie instead of 401.
                        "/api/v1/auth/logout",
                        // Actuator: ONLY health + info are publicly reachable. We deliberately
                        // do NOT permit-all on `/actuator/**` — that would auto-expose any
                        // endpoint a future contributor accidentally adds to
                        // `management.endpoints.web.exposure.include`. Exposure-list and
                        // permit-list must both name the path for a 200 response.
                        "/actuator/health",
                        "/actuator/info",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/error")
                    .permitAll()
                    // recipe-02a: GET /api/v1/recipes/{recipeId}/image is anonymous-readable
                    // (recipe images are public assets given anyone-can-read on recipes; the URL
                    // is unguessable but not secret). Only the GET verb is open — POST (upload)
                    // remains authenticated.
                    .requestMatchers(HttpMethod.GET, "/api/v1/recipes/*/image")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(AuthSecurityConfig::writeUnauthorizedProblem))
        .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void writeUnauthorizedProblem(
      HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required.");
    problem.setTitle("Unauthorized");
    problem.setType(URI.create("https://mealprep.example.com/problems/authentication-required"));
    problem.setInstance(URI.create(request.getRequestURI()));
    new MappingJackson2HttpMessageConverter()
        .getObjectMapper()
        .writeValue(response.getOutputStream(), problem);
  }
}
