package com.example.mealprep.discovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.api.internal.InHouseRobotsTxtGate;
import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-backed IT covering the four robots.txt outcomes + cache TTL behaviour. Uses the
 * test-only constructor that swaps the {@code https://} URI template for a {@code http://} template
 * aimed at the WireMock-served origin.
 */
class RobotsTxtGateIT {

  private WireMockServer wireMock;
  private String host;
  private DiscoveryProperties properties;
  private RestClient restClient;

  @BeforeEach
  void start() {
    wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wireMock.start();
    host = "localhost:" + wireMock.port();
    properties =
        new DiscoveryProperties(
            Duration.ofMinutes(10), 30, Duration.ofSeconds(60), Duration.ofHours(1));
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(2_000);
    restClient = RestClient.builder().requestFactory(factory).build();
  }

  @AfterEach
  void stop() {
    wireMock.stop();
  }

  @Test
  void allowedWhenRobotsAllowsPath() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/robots.txt"))
            .willReturn(aResponse().withStatus(200).withBody("User-agent: *\nDisallow: /admin\n")));

    InHouseRobotsTxtGate gate = gate();
    RobotsTxtOutcome outcome =
        gate.check(URI.create("http://" + host + "/recipes/abc"), "MealPrepAI/1.0");

    assertThat(outcome).isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void disallowedWhenRobotsBlocksPath() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/robots.txt"))
            .willReturn(aResponse().withStatus(200).withBody("User-agent: *\nDisallow: /admin\n")));

    InHouseRobotsTxtGate gate = gate();
    RobotsTxtOutcome outcome =
        gate.check(URI.create("http://" + host + "/admin/login"), "MealPrepAI/1.0");

    assertThat(outcome).isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void notFoundIsTreatedAsAllowed() throws Exception {
    wireMock.stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));

    InHouseRobotsTxtGate gate = gate();
    RobotsTxtOutcome outcome =
        gate.check(URI.create("http://" + host + "/anything"), "MealPrepAI/1.0");

    assertThat(outcome).isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void serverErrorIsTreatedAsUnavailable() throws Exception {
    wireMock.stubFor(get(urlPathEqualTo("/robots.txt")).willReturn(aResponse().withStatus(503)));

    InHouseRobotsTxtGate gate = gate();
    RobotsTxtOutcome outcome =
        gate.check(URI.create("http://" + host + "/anything"), "MealPrepAI/1.0");

    assertThat(outcome).isEqualTo(RobotsTxtOutcome.UNAVAILABLE);
  }

  @Test
  void secondCallHitsCacheWithinTtl() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/robots.txt"))
            .willReturn(aResponse().withStatus(200).withBody("User-agent: *\nDisallow: /admin\n")));

    InHouseRobotsTxtGate gate = gate();
    gate.check(URI.create("http://" + host + "/r/1"), "MealPrepAI/1.0");
    gate.check(URI.create("http://" + host + "/r/2"), "MealPrepAI/1.0");

    wireMock.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/robots.txt")));
  }

  @Test
  void expiredCacheTriggersRefetch() throws Exception {
    wireMock.stubFor(
        get(urlPathEqualTo("/robots.txt"))
            .willReturn(aResponse().withStatus(200).withBody("User-agent: *\nDisallow: /admin\n")));

    Instant base = Instant.parse("2026-05-13T00:00:00Z");
    AdvancingClock clock = new AdvancingClock(base);
    DiscoveryProperties shortTtl =
        new DiscoveryProperties(
            Duration.ofMinutes(10), 30, Duration.ofSeconds(60), Duration.ofMinutes(1));
    InHouseRobotsTxtGate gate = gateWithClock(shortTtl, clock);

    gate.check(URI.create("http://" + host + "/r/1"), "MealPrepAI/1.0");
    clock.advance(Duration.ofMinutes(2));
    gate.check(URI.create("http://" + host + "/r/2"), "MealPrepAI/1.0");

    wireMock.verify(2, WireMock.getRequestedFor(urlPathEqualTo("/robots.txt")));
  }

  private InHouseRobotsTxtGate gate() throws Exception {
    return gateWithClock(properties, Clock.fixed(Instant.now(), ZoneOffset.UTC));
  }

  private InHouseRobotsTxtGate gateWithClock(DiscoveryProperties props, Clock clock)
      throws Exception {
    // Use the package-private 4-arg constructor via reflection so this test class doesn't need
    // to live in the same package as InHouseRobotsTxtGate. The template substitutes {host}
    // directly into a uri-template, but RestClient URL-encodes the variable — since our test
    // host contains a port (':'), bake the WireMock origin into the template literally.
    String template = "http://localhost:" + wireMock.port() + "/robots.txt?ua={host}";
    Constructor<InHouseRobotsTxtGate> ctor =
        InHouseRobotsTxtGate.class.getDeclaredConstructor(
            RestClient.class, DiscoveryProperties.class, Clock.class, String.class);
    ctor.setAccessible(true);
    return ctor.newInstance(restClient, props, clock, template);
  }

  /** Mutable clock that advances on demand to drive the cache-TTL boundary. */
  private static final class AdvancingClock extends Clock {
    private Instant instant;

    AdvancingClock(Instant initial) {
      this.instant = initial;
    }

    void advance(Duration d) {
      this.instant = this.instant.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
