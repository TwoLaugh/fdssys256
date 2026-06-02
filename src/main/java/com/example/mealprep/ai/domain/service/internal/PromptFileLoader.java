package com.example.mealprep.ai.domain.service.internal;

import com.example.mealprep.ai.exception.AiInvalidRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads engineered dispatch prompt files ({@code prompts/<module>/<task>.txt}) off the classpath
 * and caches their bodies. These {@code .txt} files are the hand-assembled v1 dispatch prompts (see
 * {@link PromptTemplateLoader}'s class doc — that loader scans the {@code *.md} wiring docs for
 * audit; these {@code .txt} bodies are what the dispatcher actually renders and sends).
 *
 * <p>The body is immutable on the classpath for the life of the JVM, so it is read once per path
 * and memoised. A missing file is a wiring bug (a {@link TaskType} mapped in {@link PromptFiles}
 * with no resource on the classpath) — it surfaces as {@link AiInvalidRequestException}, the same
 * caller-error class the dispatcher maps to a 4xx, rather than silently falling back to a raw
 * variable dump that would reach the model.
 */
final class PromptFileLoader {

  private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

  private PromptFileLoader() {}

  /** Load (and memoise) the prompt-file body at the given classpath location. */
  static String load(String classpath) {
    return CACHE.computeIfAbsent(classpath, PromptFileLoader::readClasspath);
  }

  private static String readClasspath(String classpath) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = PromptFileLoader.class.getClassLoader();
    }
    try (InputStream in = loader.getResourceAsStream(classpath)) {
      if (in == null) {
        throw new AiInvalidRequestException(
            "Engineered prompt file not found on classpath: " + classpath);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new AiInvalidRequestException("Could not read engineered prompt file " + classpath, ex);
    }
  }
}
