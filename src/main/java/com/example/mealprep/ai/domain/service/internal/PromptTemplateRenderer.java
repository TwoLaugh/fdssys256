package com.example.mealprep.ai.domain.service.internal;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Mustache-style {@code {{variable}}} substitution. Variable names are letters / digits /
 * underscores / hyphens / dots — anything else triggers a parse error.
 *
 * <p>Edge-case decisions:
 *
 * <ul>
 *   <li>Missing variables throw {@link IllegalArgumentException} — silent {@code {{undefined}}}
 *       must never reach the upstream provider.
 *   <li>{@code null} values throw too — callers should pass an empty string explicitly when they
 *       want a blank substitution. This avoids the literal {@code "null"} substring landing in
 *       prompts where a missing field was intended.
 *   <li>Variable values are inserted verbatim. {@code {{}}} sequences in the value pass through (no
 *       double-rendering); the upstream provider treats prompt text as opaque.
 * </ul>
 */
@Component
public class PromptTemplateRenderer {

  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.\\-]+)\\s*\\}\\}");

  public String render(String template, Map<String, Object> variables) {
    Objects.requireNonNull(template, "template must not be null");
    Map<String, Object> safeVars = variables == null ? Map.of() : variables;
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1);
      if (!safeVars.containsKey(key)) {
        throw new IllegalArgumentException(
            "Missing prompt variable '" + key + "' for template substitution");
      }
      Object value = safeVars.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "Prompt variable '" + key + "' resolved to null; pass an explicit empty string");
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(value)));
    }
    matcher.appendTail(out);
    return out.toString();
  }
}
