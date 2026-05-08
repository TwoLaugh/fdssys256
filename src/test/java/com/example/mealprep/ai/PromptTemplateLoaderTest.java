package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.domain.repository.PromptTemplateRepository;
import com.example.mealprep.ai.domain.service.internal.PromptTemplateLoader;
import com.example.mealprep.ai.spi.ModelTier;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

class PromptTemplateLoaderTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-08T10:00:00Z"), ZoneOffset.UTC);

  // ---------- parse() ----------

  @Test
  void parseExtractsNameTierAndPrompts() throws Exception {
    String md = readFixture("/prompts/test-template.md");
    PromptTemplateLoader.ParsedTemplate parsed = invokeParse(md, "test-template.md");
    assertThat(parsed.name()).isEqualTo("test/loader-fixture");
    assertThat(parsed.tier()).isEqualTo(ModelTier.CHEAP);
    assertThat(parsed.systemPrompt()).contains("Echo {{INPUT}} verbatim");
    assertThat(parsed.userPromptTemplate()).contains("[Task: INGREDIENT_MAPPING]");
  }

  @Test
  void parseMapsSonnetTier() {
    String md = wiringFor("foo/bar", "Sonnet 4.6 (mid)");
    PromptTemplateLoader.ParsedTemplate parsed = invokeParse(md, "x.md");
    assertThat(parsed.tier()).isEqualTo(ModelTier.MID);
  }

  @Test
  void parseMapsOpusTier() {
    String md = wiringFor("foo/bar", "Opus 4.7 (frontier)");
    PromptTemplateLoader.ParsedTemplate parsed = invokeParse(md, "x.md");
    assertThat(parsed.tier()).isEqualTo(ModelTier.HIGH);
  }

  @Test
  void parseRejectsMissingAiTaskName() {
    String md =
        """
        # foo
        | | |
        |---|---|
        | Tier | Haiku 4.5 (cheap) |
        ## System Prompt
        ```
        s
        ```
        ## User Prompt Template
        ```
        u
        ```
        """;
    assertThatThrownBy(() -> invokeParse(md, "x.md"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AiTask name");
  }

  @Test
  void parseRejectsMissingSystemPrompt() {
    String md =
        """
        | | |
        |---|---|
        | AiTask name | `test/x` |
        | Tier | Haiku 4.5 |

        ## User Prompt Template
        ```
        u
        ```
        """;
    assertThatThrownBy(() -> invokeParse(md, "x.md"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("System Prompt");
  }

  @Test
  void parseRejectsUnrecognisedTier() {
    String md = wiringFor("foo/bar", "Llama 3 (alien)");
    assertThatThrownBy(() -> invokeParse(md, "x.md"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unrecognised tier");
  }

  // ---------- sha256Hex ----------

  @Test
  void sha256HexIsStableAndDistinct() {
    byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
    byte[] b = "world".getBytes(StandardCharsets.UTF_8);
    String hashA = invokeSha256(a);
    String hashB = invokeSha256(b);
    assertThat(hashA).hasSize(64).isEqualTo(invokeSha256(a));
    assertThat(hashA).isNotEqualTo(hashB);
  }

  // ---------- loadAll integration with stub repo ----------

  @Test
  void loadAllInsertsNewTemplate(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("01-fixture.md");
    Files.writeString(file, wiringFor("loader/integration", "Haiku 4.5"));
    StubRepository repo = new StubRepository();
    NoopPublisher publisher = new NoopPublisher();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(repo, publisher, clock, "file:" + tempDir + "/");
    int inserted = loader.loadAll();
    assertThat(inserted).isEqualTo(1);
    assertThat(repo.byName.get("loader/integration")).hasSize(1);
    PromptTemplate row = repo.byName.get("loader/integration").get(0);
    assertThat(row.getVersion()).isEqualTo(1);
    assertThat(row.getModelTier()).isEqualTo(ModelTier.CHEAP);
    assertThat(publisher.events).hasSize(1);
  }

  @Test
  void loadAllIsIdempotent(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("01-idem.md");
    Files.writeString(file, wiringFor("loader/idem", "Haiku 4.5"));
    StubRepository repo = new StubRepository();
    NoopPublisher publisher = new NoopPublisher();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(repo, publisher, clock, "file:" + tempDir + "/");
    assertThat(loader.loadAll()).isEqualTo(1);
    assertThat(loader.loadAll()).isEqualTo(0);
    assertThat(repo.byName.get("loader/idem")).hasSize(1);
    assertThat(publisher.events).hasSize(1);
  }

  @Test
  void loadAllInsertsNewVersionOnContentChange(@TempDir Path tempDir) throws Exception {
    Path file = tempDir.resolve("01-version.md");
    Files.writeString(file, wiringFor("loader/version", "Haiku 4.5"));
    StubRepository repo = new StubRepository();
    NoopPublisher publisher = new NoopPublisher();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(repo, publisher, clock, "file:" + tempDir + "/");
    loader.loadAll();
    Files.writeString(
        file,
        wiringFor("loader/version", "Haiku 4.5").replace("Echo {{INPUT}}", "Echo {{INPUT}} TWICE"));
    int second = loader.loadAll();
    assertThat(second).isEqualTo(1);
    List<PromptTemplate> versions = repo.byName.get("loader/version");
    assertThat(versions).hasSize(2);
    assertThat(versions.get(0).getVersion()).isEqualTo(1);
    assertThat(versions.get(1).getVersion()).isEqualTo(2);
    assertThat(publisher.events).hasSize(2);
  }

  @Test
  void loadAllReturnsZeroWhenSourceDirMissing(@TempDir Path tempDir) {
    StubRepository repo = new StubRepository();
    NoopPublisher publisher = new NoopPublisher();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(
            repo, publisher, clock, "file:" + tempDir.resolve("does-not-exist") + "/");
    assertThat(loader.loadAll()).isEqualTo(0);
    assertThat(repo.byName).isEmpty();
  }

  @Test
  void loadOnStartupSwallowsLoaderFailures() {
    PromptTemplateRepository explodingRepo =
        new StubRepository() {
          @Override
          public Optional<PromptTemplate> findFirstByNameOrderByVersionDesc(String name) {
            throw new IllegalStateException("boom");
          }
        };
    PromptTemplateLoader loader =
        new PromptTemplateLoader(explodingRepo, new NoopPublisher(), clock, "classpath:prompts/");
    // Should not throw — failures are logged WARN and the application continues.
    loader.loadOnStartup();
  }

  // ---------- helpers ----------

  private static String readFixture(String classpathPath) throws Exception {
    try (var in = PromptTemplateLoaderTest.class.getResourceAsStream(classpathPath)) {
      assertThat(in).as("missing test fixture %s", classpathPath).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String wiringFor(String name, String tier) {
    return """
        # Prompt — Synthetic
        ## Wiring
        | | |
        |---|---|
        | AiTask name | `%s` |
        | Tier | %s |

        ## System Prompt
        ```
        Echo {{INPUT}} verbatim.
        ```

        ## User Prompt Template
        ```
        [Task: X]

        <input>
        {{INPUT}}
        </input>
        ```
        """
        .formatted(name, tier);
  }

  private static PromptTemplateLoader.ParsedTemplate invokeParse(String content, String filename) {
    try {
      Method m = PromptTemplateLoader.class.getDeclaredMethod("parse", String.class, String.class);
      m.setAccessible(true);
      return (PromptTemplateLoader.ParsedTemplate) m.invoke(null, content, filename);
    } catch (java.lang.reflect.InvocationTargetException ex) {
      if (ex.getCause() instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(ex.getCause());
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static String invokeSha256(byte[] bytes) {
    try {
      Method m = PromptTemplateLoader.class.getDeclaredMethod("sha256Hex", byte[].class);
      m.setAccessible(true);
      return (String) m.invoke(null, (Object) bytes);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * In-memory stand-in for {@link PromptTemplateRepository}. Implements only the methods the loader
   * uses; everything else is unimplemented.
   */
  private static class StubRepository implements PromptTemplateRepository {

    final Map<String, List<PromptTemplate>> byName = new LinkedHashMap<>();
    final Map<UUID, PromptTemplate> byId = new HashMap<>();

    @Override
    public Optional<PromptTemplate> findByNameAndVersion(String name, int version) {
      return byName.getOrDefault(name, List.of()).stream()
          .filter(t -> t.getVersion() == version)
          .findFirst();
    }

    @Override
    public Optional<PromptTemplate> findFirstByNameOrderByVersionDesc(String name) {
      return byName.getOrDefault(name, List.of()).stream()
          .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()));
    }

    @Override
    public <S extends PromptTemplate> S save(S entity) {
      byId.put(entity.getId(), entity);
      byName.computeIfAbsent(entity.getName(), k -> new java.util.ArrayList<>()).add(entity);
      return entity;
    }

    // ---- Unused JpaRepository surface ----
    @Override
    public <S extends PromptTemplate> List<S> saveAll(Iterable<S> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<PromptTemplate> findById(UUID uuid) {
      return Optional.ofNullable(byId.get(uuid));
    }

    @Override
    public boolean existsById(UUID uuid) {
      return byId.containsKey(uuid);
    }

    @Override
    public List<PromptTemplate> findAll() {
      return byId.values().stream().toList();
    }

    @Override
    public List<PromptTemplate> findAllById(Iterable<UUID> uuids) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long count() {
      return byId.size();
    }

    @Override
    public void deleteById(UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(PromptTemplate entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(Iterable<? extends UUID> uuids) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll(Iterable<? extends PromptTemplate> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PromptTemplate> findAll(org.springframework.data.domain.Sort sort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public org.springframework.data.domain.Page<PromptTemplate> findAll(
        org.springframework.data.domain.Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void flush() {}

    @Override
    public <S extends PromptTemplate> S saveAndFlush(S entity) {
      return save(entity);
    }

    @Override
    public <S extends PromptTemplate> List<S> saveAllAndFlush(Iterable<S> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch(Iterable<PromptTemplate> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllByIdInBatch(Iterable<UUID> uuids) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch() {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getOne(UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getById(UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getReferenceById(UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> List<S> findAll(
        org.springframework.data.domain.Example<S> example) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> List<S> findAll(
        org.springframework.data.domain.Example<S> example,
        org.springframework.data.domain.Sort sort) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> org.springframework.data.domain.Page<S> findAll(
        org.springframework.data.domain.Example<S> example,
        org.springframework.data.domain.Pageable pageable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> long count(
        org.springframework.data.domain.Example<S> example) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> boolean exists(
        org.springframework.data.domain.Example<S> example) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate> Optional<S> findOne(
        org.springframework.data.domain.Example<S> example) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <S extends PromptTemplate, R> R findBy(
        org.springframework.data.domain.Example<S> example,
        java.util.function.Function<
                org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R>
            queryFunction) {
      throw new UnsupportedOperationException();
    }
  }

  private static class NoopPublisher implements ApplicationEventPublisher {
    final java.util.List<Object> events = new java.util.ArrayList<>();

    @Override
    public void publishEvent(ApplicationEvent event) {
      events.add(event);
    }

    @Override
    public void publishEvent(Object event) {
      events.add(event);
    }
  }
}
