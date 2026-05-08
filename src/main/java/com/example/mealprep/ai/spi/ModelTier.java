package com.example.mealprep.ai.spi;

/**
 * Cost / capability tier resolved per {@link TaskType}. Maps to a concrete Anthropic model id via
 * {@code mealprep.ai.tier-*-model} configuration.
 */
public enum ModelTier {
  CHEAP,
  MID,
  HIGH
}
