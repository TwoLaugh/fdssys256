package com.example.mealprep.grocery.api.testing;

import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider;
import com.example.mealprep.grocery.domain.service.internal.providers.FakeGroceryProvider.FailureMode;
import com.example.mealprep.grocery.domain.service.internal.providers.SubstitutionProposal;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * E2E-only HTTP control plane for the deterministic {@link FakeGroceryProvider} (grocery-01e). One
 * controller per mutator on the fake — failure-mode, delivered-flag (with optional substitutions),
 * and reset — so the grocery batch-2 e2e scenarios (GROC-15..19, XJ-05 full loop) can drive the
 * provider's behaviour over the black-box HTTP surface (decision D2) without any in-process bean
 * access.
 *
 * <p><b>Why it lives here.</b> The {@code GroceryBoundaryTest} {@code springWebStaysInApi} rule
 * forbids any class inside the {@code grocery..} package from depending on {@code
 * org.springframework.web..} unless it resides in {@code grocery.api..} or {@code
 * grocery.config..}; a controller is HTTP-layer by nature, so we place it in the {@code
 * grocery.api.testing} sub-package — boundary-compliant AND clearly signposted as test scaffolding.
 *
 * <p><b>Strictly {@code e2e}-profile-gated.</b> {@code @Profile("e2e")} means the bean (and the
 * {@code /test-support/grocery/provider/...} request mappings) do NOT exist under {@code prod} /
 * {@code dev} / {@code test}. In production the path is simply an unmapped 404. The bean's only
 * collaborator is the also-{@code @Profile("e2e")} {@link FakeGroceryProvider} bean, so this
 * controller has no reachable code path outside the e2e docker stack.
 *
 * <p><b>Reachability / security.</b> Mirrors the {@code E2eAiStubController} pattern: the e2e
 * scenario's already-authenticated session (cookie jar set by the "fresh registered and logged-in
 * user" step) satisfies the deny-by-default chain, and {@code /test-support/**} is deliberately NOT
 * in the permitAll list — an unauthenticated probe gets a clean 401. {@code OriginFilter} fast-
 * paths requests with no {@code X-Origin} header, so no {@code @OriginAware} annotation is needed.
 */
@RestController
@RequestMapping("/test-support/grocery/provider")
@Profile("e2e")
public class E2eFakeGroceryProviderController {

  private final FakeGroceryProvider fake;

  public E2eFakeGroceryProviderController(FakeGroceryProvider fake) {
    this.fake = fake;
  }

  /**
   * Arm the fake for the next provider call: failure mode + (optional) custom unavailable reason.
   * Mirrors {@link FakeGroceryProvider#setFailureMode(FailureMode)} + {@link
   * FakeGroceryProvider#setUnavailableReason(String)}.
   */
  @PostMapping(path = "/failure-mode", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void setFailureMode(@RequestBody FailureModeRequest request) {
    fake.setFailureMode(request.mode());
    if (request.unavailableReason() != null) {
      fake.setUnavailableReason(request.unavailableReason());
    }
  }

  /**
   * Configure the fake's next {@code checkStatus} call: when {@code delivered=true} the fake
   * reports {@code DELIVERED} and surfaces the supplied substitutions (each mapped to a provider
   * {@link SubstitutionProposal} via {@code originalProductId="fake-sku-"+key}, matching what the
   * happy-path {@code placeOrder} stamps on the order's lines).
   */
  @PostMapping(path = "/delivered", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void setDelivered(@RequestBody DeliveredRequest request) {
    fake.setDelivered(request.delivered());
    List<SubstitutionProposal> proposals = new ArrayList<>();
    if (request.substitutions() != null) {
      for (SubstitutionSeed seed : request.substitutions()) {
        proposals.add(
            new SubstitutionProposal(
                "fake-sku-" + seed.originalKey(),
                "Original " + seed.originalKey(),
                "fake-sku-sub-" + seed.originalKey(),
                seed.substituteName() == null ? "Substitute" : seed.substituteName(),
                seed.quantity() == null ? new BigDecimal("1.000") : seed.quantity(),
                seed.unit() == null ? "kg" : seed.unit(),
                seed.unitPence() == null ? 100 : seed.unitPence(),
                seed.reason() == null ? "out of stock" : seed.reason(),
                null));
      }
    }
    fake.setSubstitutions(proposals);
  }

  /** Reset all mutator state back to the happy path. */
  @PostMapping(path = "/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset() {
    fake.reset();
  }

  /**
   * Body for {@link #setFailureMode}. {@code unavailableReason} is optional; when {@code null} the
   * fake's existing reason is retained.
   */
  public record FailureModeRequest(FailureMode mode, String unavailableReason) {}

  /**
   * Body for {@link #setDelivered}. {@code substitutions} may be {@code null} or empty (delivery
   * with no substitutions, the straight-to-reconcile path).
   */
  public record DeliveredRequest(boolean delivered, List<SubstitutionSeed> substitutions) {}

  /**
   * Seed of a provider-surfaced substitution proposal. Only {@code originalKey} is required —
   * everything else has a sensible default so a scenario can keep the body minimal.
   */
  public record SubstitutionSeed(
      String originalKey,
      String substituteName,
      BigDecimal quantity,
      String unit,
      Integer unitPence,
      String reason) {}
}
