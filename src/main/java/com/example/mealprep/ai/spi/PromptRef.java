package com.example.mealprep.ai.spi;

/**
 * Reference to a prompt template. {@code name} follows the directory layout {@code
 * <module>/<task>}; {@code version} is a monotonically-increasing integer owned by the calling
 * module.
 *
 * <p>Resolution against the {@code prompt_template} table is owned by 01d's {@code
 * PromptTemplateService}. For 01a the dispatcher passes the ref through to the audit log and embeds
 * the rendered system prompt directly on the {@code AiTask}.
 */
public record PromptRef(String name, int version) {

  public PromptRef {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("PromptRef name must not be blank");
    }
    if (version < 1) {
      throw new IllegalArgumentException("PromptRef version must be >= 1, got " + version);
    }
  }
}
