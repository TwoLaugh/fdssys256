package com.example.mealprep.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline Spring Security 6 configuration.
 *
 * <p><b>NOTE — temporary permissive default.</b> Until ticket {@code auth-01-user-entity-and-
 * registration} lands, every request is permitted with CSRF disabled. The auth ticket replaces this
 * filter chain with a session-cookie-authenticated chain (per {@code lld/auth.md}). Do <em>not</em>
 * deploy this configuration to production as-is.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain permissiveBootstrapFilterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .build();
  }
}
