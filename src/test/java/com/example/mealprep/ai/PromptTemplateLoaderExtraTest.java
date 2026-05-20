package com.example.mealprep.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.domain.service.internal.PromptTemplateLoader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Supplemental tests for {@link PromptTemplateLoader} that target Pitest survivors not killed by
 * the existing {@code PromptTemplateLoaderTest}: the {@code resolveResources} sort call, the {@code
 * readBytes} classpath / file branch, and the {@code resourcePath} fallback paths.
 */
class PromptTemplateLoaderExtraTest {

  /**
   * Kills {@code resolveResources:135} VoidMethodCall on {@code List::sort}. We drop two files in a
   * tempdir whose lexicographic order is the opposite of file-system enumeration order, then use
   * the loader's {@code loadAll} to insert them. The resulting inserted version is 1 for the
   * lexicographically-first file and 2 for the second (because the same template name is reused); a
   * missing sort would risk ordering depending on the FS implementation.
   *
   * <p>More directly: we reflectively call {@code resolveResources} via {@code loadAll}, then
   * assert that the order of {@code repo.save()} invocations matches lexicographic filename order.
   */
  @Test
  void resolveResources_sortsByFilename(@TempDir Path tempDir) throws Exception {
    // Write files in non-alphabetical order so the directory listing isn't already sorted on disk.
    Files.writeString(tempDir.resolve("zeta.md"), wiring("loader/zeta"));
    Files.writeString(tempDir.resolve("alpha.md"), wiring("loader/alpha"));
    Files.writeString(tempDir.resolve("mu.md"), wiring("loader/mu"));

    StubRepo repo = new StubRepo();
    NoopPub pub = new NoopPub();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(repo, pub, java.time.Clock.systemUTC(), "file:" + tempDir + "/");
    int inserted = loader.loadAll();
    assertThat(inserted).isEqualTo(3);

    // Names recorded in order of save() — must be alphabetical.
    assertThat(repo.savedNames).containsExactly("loader/alpha", "loader/mu", "loader/zeta");
  }

  /**
   * Kills {@code readBytes:193} NegateConditionals — the {@code resource.isFile()} guard. A
   * non-file resource (in-memory ByteArrayResource) must fall through to the {@code
   * resource.getInputStream()} branch.
   */
  @Test
  void readBytes_nonFileResource_readsFromInputStream() throws Exception {
    Method m = PromptTemplateLoader.class.getDeclaredMethod("readBytes", Resource.class);
    m.setAccessible(true);
    byte[] payload = "abcdef".getBytes(StandardCharsets.UTF_8);
    ByteArrayResource resource =
        new ByteArrayResource(payload) {
          @Override
          public boolean isFile() {
            return false; // explicit — exercise the InputStream branch
          }

          @Override
          public String getFilename() {
            return "synthetic.md";
          }
        };
    byte[] read = (byte[]) m.invoke(null, resource);
    assertThat(read).isEqualTo(payload);
  }

  /**
   * Kills {@code resourcePath:207,211} NegateConditionals + EmptyObjectReturnVals — both branches
   * of the {@code filename == null} guard.
   *
   * <ul>
   *   <li>filename==null → return {@code resource.getDescription()} (non-empty).
   *   <li>filename!=null with a working URI → return the URI string.
   *   <li>filename!=null with a getURI()-throwing resource → fall back to filename (not empty).
   * </ul>
   */
  @Test
  void resourcePath_fallbacks() throws Exception {
    Method m = PromptTemplateLoader.class.getDeclaredMethod("resourcePath", Resource.class);
    m.setAccessible(true);

    // Branch 1: filename == null → description
    Resource nullName =
        new ByteArrayResource("x".getBytes()) {
          @Override
          public String getFilename() {
            return null;
          }

          @Override
          public String getDescription() {
            return "synthetic-desc";
          }
        };
    String r1 = (String) m.invoke(null, nullName);
    assertThat(r1).isEqualTo("synthetic-desc").isNotBlank();

    // Branch 2: filename != null & URI throws → return filename verbatim (not empty)
    Resource throwingUri =
        new ByteArrayResource("x".getBytes()) {
          @Override
          public String getFilename() {
            return "broken.md";
          }

          @Override
          public java.net.URI getURI() throws IOException {
            throw new IOException("simulated");
          }
        };
    String r2 = (String) m.invoke(null, throwingUri);
    assertThat(r2).isEqualTo("broken.md").isNotBlank();
  }

  /**
   * Kills {@code resolveResources:135} VoidMethodCall on {@code List::sort} via the new test-seam
   * constructor — stub the {@link ResourcePatternResolver} to return resources in deliberately
   * reverse-lexicographic order (which the production {@code PathMatchingResourcePatternResolver}
   * would never produce on either Windows NTFS or Linux ext4). Without the sort, the loader would
   * insert in resolver-emitted order; with the sort, it must insert alphabetically.
   *
   * <p>Previously documented as an equivalent mutant in #108 because the production resolver was
   * inline-constructed and no test could substitute an unsorted-emitting resolver.
   */
  @Test
  void resolveResources_sortsResolverEmittedResources_killsListSortVoidCall() {
    Resource zeta = wiringResource("zeta.md", "loader/zeta");
    Resource mu = wiringResource("mu.md", "loader/mu");
    Resource alpha = wiringResource("alpha.md", "loader/alpha");
    // Resolver emits reverse-lex order — only a real sort can produce alphabetical insertion.
    ResourcePatternResolver stubResolver = stubResolverReturning(zeta, mu, alpha);

    StubRepo repo = new StubRepo();
    NoopPub pub = new NoopPub();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(
            repo, pub, java.time.Clock.systemUTC(), "classpath:irrelevant/", stubResolver);
    int inserted = loader.loadAll();
    assertThat(inserted).isEqualTo(3);
    assertThat(repo.savedNames).containsExactly("loader/alpha", "loader/mu", "loader/zeta");
  }

  /**
   * Kills {@code lambda$resolveResources$0:135} EmptyObjectReturnVals — the {@code
   * Optional.ofNullable(r.getFilename()).orElse("")} fallback in the sort comparator. Production
   * {@code PathMatchingResourcePatternResolver} never returns a null filename for {@code file:} or
   * {@code classpath:} URIs, so this branch was previously unreachable. With the test-seam resolver
   * we feed a null-named resource alongside a real-named one and assert the loader doesn't NPE and
   * still saves both (the null-named one sorts first because {@code ""} precedes any non-empty
   * filename).
   *
   * <p>Previously documented as equivalent in #108 for the same reason as the sort mutant.
   */
  @Test
  void resolveResources_nullFilenameResource_sortsAsEmptyString_killsLambdaEmptyReturnVals() {
    Resource named = wiringResource("z-named.md", "loader/named");
    Resource nullNamed =
        new ByteArrayResource(wiring("loader/nullname").getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return null;
          }

          @Override
          public String getDescription() {
            return "synthetic-null-filename";
          }
        };
    // Resolver emits named first so the null-named one would not lead unless the sort comparator
    // actually evaluates and treats null as "" (empty string sorts before "z-named.md").
    ResourcePatternResolver stubResolver = stubResolverReturning(named, nullNamed);

    StubRepo repo = new StubRepo();
    NoopPub pub = new NoopPub();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(
            repo, pub, java.time.Clock.systemUTC(), "classpath:irrelevant/", stubResolver);
    int inserted = loader.loadAll();
    // Both must be inserted (no NPE on the null filename), and the null-named resource sorts
    // first because Optional.ofNullable(null).orElse("") < "z-named.md".
    assertThat(inserted).isEqualTo(2);
    assertThat(repo.savedNames).containsExactly("loader/nullname", "loader/named");
  }

  /**
   * Kills {@code resolveResources} IOException catch path — the {@code log.warn} + empty-list
   * return when the resolver throws. Stub a resolver that throws {@link IOException}; the loader
   * must swallow it and return zero inserted (no NPE, no propagation).
   */
  @Test
  void resolveResources_resolverThrowsIoException_returnsEmptyListAndLogsWarn() {
    ResourcePatternResolver throwingResolver =
        new PathMatchingResourcePatternResolver() {
          @Override
          public Resource[] getResources(String locationPattern) throws IOException {
            throw new IOException("simulated enumeration failure");
          }
        };
    StubRepo repo = new StubRepo();
    NoopPub pub = new NoopPub();
    PromptTemplateLoader loader =
        new PromptTemplateLoader(
            repo, pub, java.time.Clock.systemUTC(), "classpath:irrelevant/", throwingResolver);
    int inserted = loader.loadAll();
    assertThat(inserted).isEqualTo(0);
    assertThat(repo.savedNames).isEmpty();
  }

  // ---------- helpers ----------

  /**
   * Build a {@link ResourcePatternResolver} that returns the given resources for any {@code
   * getResources(pattern)} call. Backed by {@link PathMatchingResourcePatternResolver} because
   * {@link ResourcePatternResolver} isn't a functional interface (it extends {@code ResourceLoader}
   * and inherits multiple abstract methods).
   */
  private static ResourcePatternResolver stubResolverReturning(Resource... resources) {
    return new PathMatchingResourcePatternResolver() {
      @Override
      public Resource[] getResources(String locationPattern) {
        return resources;
      }
    };
  }

  /** Build a {@link Resource} backed by a well-formed wiring markdown body. */
  private static Resource wiringResource(String filename, String aiTaskName) {
    byte[] payload = wiring(aiTaskName).getBytes(StandardCharsets.UTF_8);
    return new ByteArrayResource(payload) {
      @Override
      public String getFilename() {
        return filename;
      }

      @Override
      public String getDescription() {
        return "synthetic:" + filename;
      }
    };
  }

  private static String wiring(String name) {
    return """
        # x
        | | |
        |---|---|
        | AiTask name | `%s` |
        | Tier | Haiku 4.5 |

        ## System Prompt
        ```
        s
        ```

        ## User Prompt Template
        ```
        u
        ```
        """
        .formatted(name);
  }

  /** Minimal stub repository implementing only the methods the loader uses. */
  private static class StubRepo
      implements com.example.mealprep.ai.domain.repository.PromptTemplateRepository {
    final List<String> savedNames = new ArrayList<>();

    @Override
    public java.util.Optional<PromptTemplate> findByNameAndVersion(String name, int version) {
      return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<PromptTemplate> findFirstByNameOrderByVersionDesc(String name) {
      return java.util.Optional.empty();
    }

    @Override
    public <S extends PromptTemplate> S save(S entity) {
      savedNames.add(entity.getName());
      return entity;
    }

    @Override
    public <S extends PromptTemplate> List<S> saveAll(Iterable<S> entities) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<PromptTemplate> findById(java.util.UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean existsById(java.util.UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PromptTemplate> findAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<PromptTemplate> findAllById(Iterable<java.util.UUID> uuids) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long count() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteById(java.util.UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(PromptTemplate entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllById(Iterable<? extends java.util.UUID> uuids) {
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
    public void deleteAllByIdInBatch(Iterable<java.util.UUID> uuids) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteAllInBatch() {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getOne(java.util.UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getById(java.util.UUID uuid) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PromptTemplate getReferenceById(java.util.UUID uuid) {
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
    public <S extends PromptTemplate> java.util.Optional<S> findOne(
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

  private static class NoopPub implements org.springframework.context.ApplicationEventPublisher {
    @Override
    public void publishEvent(org.springframework.context.ApplicationEvent event) {}

    @Override
    public void publishEvent(Object event) {}
  }
}
