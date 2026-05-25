package com.example.mealprep.preference.testing;

import com.example.mealprep.auth.domain.service.CurrentUserResolver;
import com.example.mealprep.preference.api.dto.HardConstraintsDto;
import com.example.mealprep.preference.api.dto.LifestyleConfigDto;
import com.example.mealprep.preference.api.dto.TasteProfileDto;
import com.example.mealprep.preference.api.dto.UpdateLifestyleConfigRequest;
import com.example.mealprep.preference.domain.document.LifestyleConfigDocument;
import com.example.mealprep.preference.domain.service.LifestyleConfigUpdateService;
import com.example.mealprep.preference.domain.service.PreferenceUpdateService;
import com.example.mealprep.preference.domain.service.TasteProfileUpdateService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * E2E-only HTTP control plane for SEEDING the authenticated user's preference aggregates.
 *
 * <p><b>Why this exists.</b> A fresh, just-registered user has NO preference aggregates and there
 * is NO public HTTP path to create one — the three {@code initialise} flows ({@code
 * PreferenceUpdateService#initialiseHardConstraints}, {@code TasteProfileUpdateService#initialise},
 * {@code LifestyleConfigUpdateService#initialise}) are in-process service calls invoked by the
 * onboarding wizard / the nutrition→preference directive-apply path / ITs, never exposed on the
 * product REST surface. So for the black-box E2E suite (decision D2, HTTP/JSON only) every
 * GET/PUT/refresh on an aggregate 404s for a fresh user, which blocks every positive-path scenario.
 * This controller seeds the REALISTIC initial state by invoking those SAME real {@code initialise}
 * services, so a scenario can then exercise the genuine create/edit/refresh behaviour over real
 * HTTP — standard E2E fixture practice, mirroring {@code E2eAiStubController} (the AI stub seam).
 *
 * <p><b>Access seam — public service interfaces, no widening.</b> All three {@code initialise}
 * methods are already declared on the public {@code domain.service} interfaces (not just the {@code
 * .internal.} impls), so this controller injects those interfaces directly. No new public method
 * was added and no package-private seam was reached into — the seeder uses exactly the API the
 * product's own onboarding/directive code uses, so the seeded state is identical to what the system
 * itself creates (e.g. {@code initialiseHardConstraints} → omnivore-default empty aggregate; {@code
 * TasteProfileUpdateService#initialise} → empty document at {@code documentVersion=1}). The
 * lifestyle {@code initialise} requires an onboarding document; we pass {@link
 * LifestyleConfigDocument#empty()} — the canonical seed shape that record exposes for exactly this
 * purpose (all sections null), so the seeded config matches the product's first-write state.
 *
 * <p><b>Current user resolution.</b> Resolved server-side via {@link CurrentUserResolver} — the
 * same pattern every real preference controller uses ({@code HardConstraintsController} / {@code
 * TasteProfileController} / {@code LifestyleConfigController}). The seeder never accepts a {@code
 * userId} param, so a scenario can only ever seed ITS OWN authenticated user's state (self-scoped,
 * D5).
 *
 * <p><b>Strictly {@code e2e}-profile-gated.</b> {@code @Profile("e2e")} means this bean (and the
 * {@code /test-support/preference/**} request mappings) do NOT exist under {@code prod}/{@code
 * dev}/{@code test}. In production the path is an unmapped 404 — never a live attack surface. It
 * stays behind the deny-by-default auth chain (the E2E client is an authenticated cookie session);
 * {@code /test-support/**} is deliberately NOT added to any permitAll whitelist and no security or
 * {@code OriginFilter} change is needed (same reachability facts documented on {@code
 * E2eAiStubController}: USER-origin requests carry no {@code X-Origin} header, so the origin filter
 * fast-path lets them through, and the authenticated session satisfies {@code
 * anyRequest().authenticated()}).
 */
@RestController
@RequestMapping("/test-support/preference")
@Profile("e2e")
@Tag(name = "E2E Test Support")
public class E2ePreferenceSeedController {

  private final PreferenceUpdateService preferenceUpdateService;
  private final TasteProfileUpdateService tasteProfileUpdateService;
  private final LifestyleConfigUpdateService lifestyleConfigUpdateService;
  private final CurrentUserResolver currentUserResolver;

  public E2ePreferenceSeedController(
      PreferenceUpdateService preferenceUpdateService,
      TasteProfileUpdateService tasteProfileUpdateService,
      LifestyleConfigUpdateService lifestyleConfigUpdateService,
      CurrentUserResolver currentUserResolver) {
    this.preferenceUpdateService = preferenceUpdateService;
    this.tasteProfileUpdateService = tasteProfileUpdateService;
    this.lifestyleConfigUpdateService = lifestyleConfigUpdateService;
    this.currentUserResolver = currentUserResolver;
  }

  /**
   * Seed the calling user's Tier-1 hard-constraints aggregate with the product's omnivore defaults
   * (empty allergies/intolerances/medical-diets, {@code base = "omnivore"}). Idempotent at the
   * service level. Returns the created aggregate so a scenario can read back the seeded version.
   */
  @PostMapping(path = "/hard-constraints/seed", produces = MediaType.APPLICATION_JSON_VALUE)
  public HardConstraintsDto seedHardConstraints() {
    return preferenceUpdateService.initialiseHardConstraints(requireCurrentUserId());
  }

  /**
   * Seed the calling user's Tier-2 taste profile with the product's empty initial document (at
   * {@code documentVersion = 1}). Idempotent at the service level. Returns the created aggregate.
   */
  @PostMapping(path = "/taste-profile/seed", produces = MediaType.APPLICATION_JSON_VALUE)
  public TasteProfileDto seedTasteProfile() {
    return tasteProfileUpdateService.initialise(requireCurrentUserId());
  }

  /**
   * Seed the calling user's Tier-3 lifestyle config with the canonical empty onboarding document
   * ({@link LifestyleConfigDocument#empty()} — all sections null, exactly the seed shape {@code
   * initialise} is designed to accept). Idempotent at the service level. Returns the created
   * aggregate.
   */
  @PostMapping(path = "/lifestyle-config/seed", produces = MediaType.APPLICATION_JSON_VALUE)
  public LifestyleConfigDto seedLifestyleConfig() {
    UpdateLifestyleConfigRequest request =
        new UpdateLifestyleConfigRequest(LifestyleConfigDocument.empty(), 0L);
    return lifestyleConfigUpdateService.initialise(requireCurrentUserId(), request);
  }

  private UUID requireCurrentUserId() {
    return currentUserResolver
        .currentUserId()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required."));
  }
}
