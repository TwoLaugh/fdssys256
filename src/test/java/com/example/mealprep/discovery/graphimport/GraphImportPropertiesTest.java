package com.example.mealprep.discovery.graphimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.discovery.config.GraphImportConfig;
import com.example.mealprep.discovery.config.GraphImportProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * G11: binding + defaults for the graph-import flag pair. The gate is only real if a fresh context
 * with no overrides yields both flags {@code false} (standing law 7 — no exposure until the §7
 * quality gate passes), and if no profile properties file quietly flips them on (the e2e landmine:
 * an accidentally-on flag in a shared dev DB is exactly how an unreviewed dish reaches a demo).
 */
class GraphImportPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(GraphImportConfig.class);

  @Test
  void freshContextNoOverrides_bothFlagsDefaultFalse() {
    runner.run(
        ctx -> {
          GraphImportProperties props = ctx.getBean(GraphImportProperties.class);
          assertThat(props.enabled()).isFalse();
          assertThat(props.allowRestrictedDietFlags()).isFalse();
        });
  }

  @Test
  void bothKeysBindFromTheDocumentedPropertyNames() {
    runner
        .withPropertyValues(
            "mealprep.graph.import.enabled=true",
            "mealprep.graph.import.allow-restricted-diet-flags=true")
        .run(
            ctx -> {
              GraphImportProperties props = ctx.getBean(GraphImportProperties.class);
              assertThat(props.enabled()).isTrue();
              assertThat(props.allowRestrictedDietFlags()).isTrue();
            });
  }

  /**
   * Pin: every {@code application*.properties} on the main classpath keeps {@code
   * mealprep.graph.import.*} at {@code false}, and the base file declares both keys explicitly (the
   * comment next to the key is where the flag-off-does-not-retro-hide asymmetry is documented).
   * Scans the files on disk so the test classpath's own {@code application.properties} cannot
   * shadow the production one.
   */
  @Test
  void noProfilePropertiesFileFlipsAGraphImportFlagOn() throws IOException {
    Path resources = Path.of("src", "main", "resources");
    List<Path> files;
    try (Stream<Path> listing = Files.list(resources)) {
      files =
          listing
              .filter(p -> p.getFileName().toString().matches("application(-\\w+)?\\.properties"))
              .sorted()
              .toList();
    }
    assertThat(files).isNotEmpty();

    boolean baseDeclaresBoth = false;
    for (Path file : files) {
      Properties props = new Properties();
      try (InputStream in = Files.newInputStream(file)) {
        props.load(in);
      }
      for (String key : props.stringPropertyNames()) {
        if (key.startsWith("mealprep.graph.import.")) {
          assertThat(props.getProperty(key))
              .as("%s must keep %s off by default", file.getFileName(), key)
              .isEqualTo("false");
        }
      }
      if (file.getFileName().toString().equals("application.properties")) {
        baseDeclaresBoth =
            "false".equals(props.getProperty("mealprep.graph.import.enabled"))
                && "false"
                    .equals(props.getProperty("mealprep.graph.import.allow-restricted-diet-flags"));
      }
    }
    assertThat(baseDeclaresBoth)
        .as("application.properties declares both graph-import flags explicitly false")
        .isTrue();
  }
}
