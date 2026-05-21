package com.example.mealprep.discovery.api.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.example.mealprep.discovery.config.DiscoveryProperties;
import com.example.mealprep.discovery.domain.entity.RobotsTxtOutcome;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Pure-unit coverage of {@link InHouseRobotsTxtGate} (a {@code @Component} in {@code
 * discovery.api.internal}). Uses WireMock for the HTTP boundary; no Spring context.
 *
 * <p>Pitest measures the in-house parser + outcome-resolution branches: UA matching, wildcard
 * fallback, longest-token preference, comment stripping, adjacent UA blocks, 404 → allow,
 * RestClient errors → unavailable, cache TTL hit vs miss.
 */
class InHouseRobotsTxtGateTest {

  private WireMockServer wm;
  private InHouseRobotsTxtGate gate;
  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-05-13T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wm.start();
    WireMock.configureFor("localhost", wm.port());
    RestClient restClient = RestClient.builder().build();
    DiscoveryProperties props =
        new DiscoveryProperties(
            Duration.ofMinutes(10),
            30,
            Duration.ofSeconds(60),
            Duration.ofHours(1),
            Duration.ofHours(6),
            null);
    String template = String.format("http://localhost:%d/{host}/robots.txt", wm.port());
    gate = new InHouseRobotsTxtGate(restClient, props, fixedClock, template);
  }

  @AfterEach
  void tearDown() {
    wm.stop();
  }

  private void stubRobots(String host, int status, String body) {
    stubFor(
        get(urlMatching("/" + host + "/robots.txt"))
            .willReturn(aResponse().withStatus(status).withBody(body == null ? "" : body)));
  }

  private void stubRobots404(String host) {
    stubFor(get(urlMatching("/" + host + "/robots.txt")).willReturn(aResponse().withStatus(404)));
  }

  private void stubRobots500(String host) {
    stubFor(get(urlMatching("/" + host + "/robots.txt")).willReturn(aResponse().withStatus(500)));
  }

  @Test
  void check_nullHostInUri_returnsUnavailable() {
    // kills NegateConditionalsMutator at line 75 (`host == null`).
    RobotsTxtOutcome outcome = gate.check(URI.create("file:/no-host"), "UA");
    assertThat(outcome).isEqualTo(RobotsTxtOutcome.UNAVAILABLE);
  }

  @Test
  void check_robotsTxt404_treatedAsAllowAll() {
    stubRobots404("a.test");

    RobotsTxtOutcome outcome = gate.check(URI.create("http://a.test/path"), "UA");
    assertThat(outcome).isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_robotsTxt500_treatedAsUnavailable() {
    stubRobots500("b.test");

    RobotsTxtOutcome outcome = gate.check(URI.create("http://b.test/path"), "UA");
    assertThat(outcome).isEqualTo(RobotsTxtOutcome.UNAVAILABLE);
  }

  @Test
  void check_disallowExactMatch_wildcardUa_returnsDisallowed() {
    stubRobots("c.test", 200, "User-agent: *\nDisallow: /private\n");

    assertThat(gate.check(URI.create("http://c.test/private"), "UA"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_disallowPrefix_path_returnsDisallowed() {
    stubRobots("d.test", 200, "User-agent: *\nDisallow: /private\n");

    assertThat(gate.check(URI.create("http://d.test/private/a"), "UA"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_disallowNotMatchingPath_returnsAllowed() {
    stubRobots("e.test", 200, "User-agent: *\nDisallow: /private\n");

    assertThat(gate.check(URI.create("http://e.test/public"), "UA"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_specificUaTakesPrecedenceOverWildcard() {
    stubRobots("f.test", 200, "User-agent: *\nDisallow: /a\n\nUser-agent: mybot\nDisallow: /b\n");

    // path /a is wildcard-disallowed but NOT in mybot's list → mybot is ALLOWED for /a.
    assertThat(gate.check(URI.create("http://f.test/a"), "MyBot/1.0"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
    // path /b is mybot-disallowed.
    assertThat(gate.check(URI.create("http://f.test/b"), "MyBot/1.0"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_commentsAreStrippedBeforeParsing() {
    stubRobots("g.test", 200, "User-agent: *\n#Disallow: /commented\n");

    assertThat(gate.check(URI.create("http://g.test/commented"), "UA"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_blankBody_allowsAll() {
    stubRobots("h.test", 200, "");

    assertThat(gate.check(URI.create("http://h.test/anything"), "UA"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_lineWithoutColon_skipped() {
    stubRobots("i.test", 200, "This line has no colon\nUser-agent: *\nDisallow: /priv\n");

    assertThat(gate.check(URI.create("http://i.test/priv"), "UA"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_emptyDisallowValue_doesNotAddRule() {
    stubRobots("j.test", 200, "User-agent: *\nDisallow:\n");

    assertThat(gate.check(URI.create("http://j.test/whatever"), "UA"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_disallowWithoutPriorUserAgent_ignored() {
    stubRobots("k.test", 200, "Disallow: /lonely\n");

    assertThat(gate.check(URI.create("http://k.test/lonely"), "UA"))
        .isEqualTo(RobotsTxtOutcome.ALLOWED);
  }

  @Test
  void check_adjacentUserAgentsShareRules() {
    stubRobots("l.test", 200, "User-agent: bot1\nUser-agent: bot2\nDisallow: /shared\n");

    assertThat(gate.check(URI.create("http://l.test/shared"), "bot1"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
    assertThat(gate.check(URI.create("http://l.test/shared"), "bot2"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_normalisesEmptyPathToSlash() {
    stubRobots("m.test", 200, "User-agent: *\nDisallow: /\n");

    assertThat(gate.check(URI.create("http://m.test"), "UA"))
        .isEqualTo(RobotsTxtOutcome.DISALLOWED);
  }

  @Test
  void check_secondCallSameHost_usesCacheNoSecondHttp() {
    stubRobots("n.test", 200, "User-agent: *\nDisallow: /x\n");

    gate.check(URI.create("http://n.test/x"), "UA");
    gate.check(URI.create("http://n.test/x"), "UA");

    // Only one HTTP request expected (the second is served from cache).
    wm.verify(1, RequestPatternBuilder.allRequests());
  }

  @Test
  void check_doesNotThrowOnCommentOnlyLine() {
    stubRobots("o.test", 200, "# just a comment\n");

    assertThatNoException().isThrownBy(() -> gate.check(URI.create("http://o.test/x"), "UA"));
  }
}
