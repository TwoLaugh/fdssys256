package com.example.mealprep.auth.config;

import com.example.mealprep.auth.domain.entity.Session;
import com.example.mealprep.auth.domain.entity.User;
import com.example.mealprep.auth.domain.repository.SessionRepository;
import com.example.mealprep.auth.domain.repository.UserRepository;
import com.example.mealprep.auth.domain.service.AuthenticatedPrincipal;
import com.example.mealprep.auth.domain.service.internal.SessionTokenGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the {@code AUTH_SESSION} cookie, looks up the session by its SHA-256 hash, and attaches an
 * {@link AuthenticatedPrincipal} to the {@code SecurityContext}. Missing / invalid / expired /
 * revoked / soft-deleted-user sessions leave the context anonymous; downstream {@code
 * authorizeHttpRequests} rules then handle the 401.
 *
 * <p>Registered before {@code UsernamePasswordAuthenticationFilter} in {@code AuthSecurityConfig}.
 * Runs once per request.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  private static final List<SimpleGrantedAuthority> ROLE_USER =
      List.of(new SimpleGrantedAuthority("ROLE_USER"));

  private final SessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final SessionTokenGenerator tokenGenerator;
  private final String cookieName;
  private final Clock clock;

  public SessionAuthenticationFilter(
      SessionRepository sessionRepository,
      UserRepository userRepository,
      SessionTokenGenerator tokenGenerator,
      AuthProperties authProperties,
      Clock clock) {
    this.sessionRepository = sessionRepository;
    this.userRepository = userRepository;
    this.tokenGenerator = tokenGenerator;
    this.cookieName = authProperties.cookieName();
    this.clock = clock;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      authenticate(request);
    } catch (RuntimeException ex) {
      // Filter must never throw — that would expose details to the error page. On any internal
      // failure, log and proceed anonymous so the chain returns 401 from authorize rules.
      logger.warn("session authentication lookup failed; treating request as anonymous", ex);
      SecurityContextHolder.clearContext();
    }
    chain.doFilter(request, response);
  }

  private void authenticate(HttpServletRequest request) {
    String rawToken = readCookie(request);
    if (rawToken == null || rawToken.isEmpty()) {
      return;
    }
    String hash = tokenGenerator.hash(rawToken);
    Optional<Session> sessionOpt = sessionRepository.findByTokenHash(hash);
    if (sessionOpt.isEmpty()) {
      return;
    }
    Session session = sessionOpt.get();
    Instant now = Instant.now(clock);
    if (session.getRevokedAt() != null
        || session.getExpiresAt() == null
        || !now.isBefore(session.getExpiresAt())) {
      return;
    }
    Optional<User> userOpt = userRepository.findById(session.getUserId());
    if (userOpt.isEmpty() || userOpt.get().getDeletedAt() != null) {
      return;
    }
    AuthenticatedPrincipal principal =
        new AuthenticatedPrincipal(userOpt.get().getId(), session.getId());
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, ROLE_USER);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String readCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
