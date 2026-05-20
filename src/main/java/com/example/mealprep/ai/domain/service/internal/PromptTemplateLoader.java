package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.domain.entity.PromptTemplate;
import com.example.mealprep.ai.domain.repository.PromptTemplateRepository;
import com.example.mealprep.ai.event.PromptTemplateLoadedEvent;
import com.example.mealprep.ai.spi.ModelTier;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads prompt templates from {@code mealprep.ai.prompt-templates-source-path} into the {@code
 * ai_prompt_template} table at startup. Idempotent: same {@code source_hash} → no DB write.
 * Append-only: a content change INSERTs a new {@code version} row alongside the old.
 *
 * <p>Default source location is the classpath ({@code classpath:prompts/*.md}); a Maven {@code
 * <resource>} block in {@code pom.xml} copies {@code lld/prompts/} into the JAR under {@code
 * /prompts/}. Override for tests via {@code mealprep.ai.prompt-templates-source-path=classpath
 * :test-prompts/} or a filesystem path.
 *
 * <p>Failures are logged at WARN and the application continues — production callers fall back to
 * in-memory templates supplied via {@code AiTask.getSystemPrompt()}.
 */
@Component
public class PromptTemplateLoader {

  private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

  static final String DEFAULT_SOURCE_PATH = "classpath:prompts/";

  private static final Pattern AI_TASK_NAME_ROW =
      Pattern.compile(
          "\\|\\s*AiTask\\s+name\\s*\\|\\s*`?([a-zA-Z0-9_.\\-/]+)`?\\s*\\|", Pattern.MULTILINE);
  private static final Pattern TIER_ROW =
      Pattern.compile(
          "\\|\\s*Tier\\s*\\|\\s*([^|]*?)\\s*\\|", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
  // \R matches any line terminator (LF, CRLF, CR) so the loader handles git's autocrlf
  // checkout on Windows alongside the LF-only files in lld/prompts/.
  private static final Pattern SYSTEM_PROMPT_BLOCK =
      Pattern.compile(
          "##\\s+System\\s+Prompt\\s*\\R+```[a-zA-Z0-9_-]*\\R(.*?)\\R```",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
  private static final Pattern USER_PROMPT_BLOCK =
      Pattern.compile(
          "##\\s+User\\s+Prompt\\s+Template\\s*\\R+```[a-zA-Z0-9_-]*\\R(.*?)\\R```",
          Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

  private final PromptTemplateRepository repository;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock;
  private final ResourcePatternResolver resourceResolver;

  private final String sourcePath;

  public PromptTemplateLoader(
      PromptTemplateRepository repository,
      ApplicationEventPublisher eventPublisher,
      Clock clock,
      @Value("${mealprep.ai.prompt-templates-source-path:" + DEFAULT_SOURCE_PATH + "}")
          String sourcePath) {
    this(repository, eventPublisher, clock, sourcePath, new PathMatchingResourcePatternResolver());
  }

  /**
   * Test-seam constructor: lets unit tests substitute a stub {@link ResourcePatternResolver} so the
   * {@code resolveResources} sort + null-filename branches are reachable from pure-unit tests
   * (previously a documented-equivalent mutant in #108 because the production resolver never emits
   * unsorted or null-named resources for {@code file:} / {@code classpath:} URIs).
   */
  public PromptTemplateLoader(
      PromptTemplateRepository repository,
      ApplicationEventPublisher eventPublisher,
      Clock clock,
      String sourcePath,
      ResourcePatternResolver resourceResolver) {
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
    this.sourcePath = sourcePath;
    this.resourceResolver = resourceResolver;
  }

  @PostConstruct
  public void loadOnStartup() {
    try {
      loadAll();
    } catch (RuntimeException ex) {
      // Loader failures must not prevent application startup.
      log.warn(
          "prompt-template loader failed; continuing without DB-backed templates source={} reason={}",
          sourcePath,
          ex.getMessage());
    }
  }

  /**
   * Public-for-tests entry point. Returns the count of newly-inserted versions (no-ops are counted
   * as 0).
   */
  public int loadAll() {
    List<Resource> resources = resolveResources();
    if (resources.isEmpty()) {
      log.info("prompt-template loader found no markdown files at {}", sourcePath);
      return 0;
    }
    int inserted = 0;
    for (Resource resource : resources) {
      try {
        if (loadOne(resource)) {
          inserted++;
        }
      } catch (RuntimeException ex) {
        log.warn(
            "prompt-template loader skipped {} reason={}", resource.getFilename(), ex.getMessage());
      }
    }
    log.info("prompt-template loader processed scanned={} inserted={}", resources.size(), inserted);
    return inserted;
  }

  private List<Resource> resolveResources() {
    String pattern = normalisePattern(sourcePath);
    try {
      Resource[] resources = resourceResolver.getResources(pattern);
      List<Resource> ordered = new ArrayList<>(List.of(resources));
      ordered.sort(Comparator.comparing(r -> Optional.ofNullable(r.getFilename()).orElse("")));
      return ordered;
    } catch (IOException ex) {
      log.warn("prompt-template loader could not enumerate {} reason={}", pattern, ex.getMessage());
      return List.of();
    }
  }

  private static String normalisePattern(String sourcePath) {
    String trimmed = sourcePath.trim();
    if (trimmed.endsWith(".md")) {
      return trimmed;
    }
    String withSlash = trimmed.endsWith("/") ? trimmed : trimmed + "/";
    return withSlash + "*.md";
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  protected boolean loadOne(Resource resource) {
    byte[] bytes = readBytes(resource);
    String content = new String(bytes, StandardCharsets.UTF_8);
    String hash = sha256Hex(bytes);
    ParsedTemplate parsed = parse(content, resource.getFilename());

    Optional<PromptTemplate> latest = repository.findFirstByNameOrderByVersionDesc(parsed.name());
    if (latest.isPresent() && latest.get().getSourceHash().equals(hash)) {
      return false;
    }
    int nextVersion = latest.map(t -> t.getVersion() + 1).orElse(1);
    UUID id = UUID.randomUUID();
    String sourceFile = resourcePath(resource);
    PromptTemplate row =
        new PromptTemplate(
            id,
            parsed.name(),
            nextVersion,
            parsed.tier(),
            parsed.systemPrompt(),
            parsed.userPromptTemplate(),
            null,
            null,
            parsed.notes(),
            sourceFile,
            hash);
    repository.save(row);
    eventPublisher.publishEvent(
        new PromptTemplateLoadedEvent(
            id, parsed.name(), nextVersion, UUID.randomUUID(), Instant.now(clock)));
    log.info(
        "prompt-template loader inserted name={} version={} hash={}",
        parsed.name(),
        nextVersion,
        hash);
    return true;
  }

  private static byte[] readBytes(Resource resource) {
    try {
      if (resource.isFile()) {
        return Files.readAllBytes(Path.of(resource.getURI()));
      }
      try (InputStream in = resource.getInputStream()) {
        return in.readAllBytes();
      }
    } catch (IOException ex) {
      throw new IllegalStateException(
          "Could not read prompt template " + resource.getDescription(), ex);
    }
  }

  private static String resourcePath(Resource resource) {
    String filename = resource.getFilename();
    if (filename == null) {
      return resource.getDescription();
    }
    try {
      return resource.getURI().toString();
    } catch (IOException ex) {
      return filename;
    }
  }

  /**
   * Parses a single markdown file. Visible for testing — the format is the structured convention
   * from {@code lld/prompts/README.md}.
   */
  static ParsedTemplate parse(String content, String filename) {
    Matcher nameMatcher = AI_TASK_NAME_ROW.matcher(content);
    if (!nameMatcher.find()) {
      throw new IllegalArgumentException(
          "prompt template " + filename + " missing 'AiTask name' row in wiring table");
    }
    String name = nameMatcher.group(1).trim();

    Matcher tierMatcher = TIER_ROW.matcher(content);
    if (!tierMatcher.find()) {
      throw new IllegalArgumentException(
          "prompt template " + filename + " missing 'Tier' row in wiring table");
    }
    ModelTier tier = mapTier(tierMatcher.group(1).trim(), filename);

    Matcher systemMatcher = SYSTEM_PROMPT_BLOCK.matcher(content);
    if (!systemMatcher.find()) {
      throw new IllegalArgumentException(
          "prompt template " + filename + " missing fenced code block under '## System Prompt'");
    }
    String systemPrompt = systemMatcher.group(1).trim();

    Matcher userMatcher = USER_PROMPT_BLOCK.matcher(content);
    if (!userMatcher.find()) {
      throw new IllegalArgumentException(
          "prompt template "
              + filename
              + " missing fenced code block under '## User Prompt Template'");
    }
    String userPrompt = userMatcher.group(1).trim();

    return new ParsedTemplate(name, tier, systemPrompt, userPrompt, "Loaded from " + filename);
  }

  private static ModelTier mapTier(String raw, String filename) {
    String lower = raw.toLowerCase();
    if (lower.contains("haiku") || lower.contains("cheap")) {
      return ModelTier.CHEAP;
    }
    if (lower.contains("sonnet") || lower.contains("mid")) {
      return ModelTier.MID;
    }
    if (lower.contains("opus") || lower.contains("high") || lower.contains("frontier")) {
      return ModelTier.HIGH;
    }
    throw new IllegalArgumentException(
        "prompt template " + filename + " has unrecognised tier '" + raw + "'");
  }

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hashed = digest.digest(bytes);
      StringBuilder sb = new StringBuilder(hashed.length * 2);
      for (byte b : hashed) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
    }
  }

  /** Visible-for-tests parsing result. */
  public record ParsedTemplate(
      String name, ModelTier tier, String systemPrompt, String userPromptTemplate, String notes) {}
}
