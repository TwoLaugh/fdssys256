package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.ActiveExperiment;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.SoftIntolerance;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TrendingIngredient;
import com.example.mealprep.preference.domain.entity.TasteProfile;
import com.example.mealprep.preference.domain.repository.TasteProfileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composes the deterministic text input the {@code OpenAI text-embedding-3-small} model embeds for
 * a user's {@link TasteProfileDocument}. Mirrors {@code
 * recipe.domain.service.internal.RecipeEmbeddingInputBuilder} in intent: a stable, byte-identical
 * serialisation of the document's structured taste signals so the same document always yields the
 * same embedding input (and so the AI-module embedding cache de-dupes unchanged documents).
 *
 * <p>The serialisation is a flat, labelled feature string covering the dimensions that carry taste
 * meaning — flavour, texture, ingredient likes/dislikes, cuisine, cooking style, portion, soft
 * intolerances, household-suitability notes, active-experiment hypotheses, and learned insights.
 * Volatile / non-semantic fields (versions, cursors, evidence counts, dates) are deliberately
 * excluded so a delta that only bumps an evidence count does not change the embedding input (the
 * cache then returns the cached vector — no needless re-embed).
 *
 * <p>Ordering is the document's own list ordering (which the delta applier maintains
 * deterministically), so the output is reproducible across JVMs. Pure function of the document — no
 * I/O, no DB touch — so it is trivially unit-testable and the listener can call it on the loaded
 * document directly.
 */
@Component
public class TasteProfileEmbeddingInputBuilder {

  private final TasteProfileRepository repository;

  public TasteProfileEmbeddingInputBuilder(TasteProfileRepository repository) {
    this.repository = repository;
  }

  /**
   * Load the current taste profile for {@code userId} and compose its embedding input in a short
   * {@code REQUIRES_NEW readOnly} transaction. The async listener calls this as its first JPA touch
   * on a fresh {@code @Async} thread (which has no inherited transaction); a separate bean (not a
   * self-invoked method) is required so the {@code @Transactional} proxy actually engages — same
   * structural reason {@code RecipeEmbeddingInputBuilder.loadAndCompose} is its own bean. Returns
   * {@code null} when the profile no longer exists (hard-deleted between publish and execution).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public LoadedInput loadAndCompose(UUID userId) {
    Optional<TasteProfile> profile = repository.findByUserId(userId);
    if (profile.isEmpty()) {
      return null;
    }
    TasteProfile tp = profile.get();
    return new LoadedInput(tp.getDocumentVersion(), compose(tp.getDocument()));
  }

  /** The document version the input was composed from + the composed input text. */
  public record LoadedInput(int documentVersion, String inputText) {}

  /**
   * Compose the embedding input for {@code document}. Returns a single normalised line; returns the
   * empty string for a {@code null} document (the listener treats blank as "nothing to embed").
   * Pure function — exposed for unit testing the serialisation independent of the DB load.
   */
  public String compose(TasteProfileDocument document) {
    if (document == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();

    if (document.flavourPreferences() != null) {
      addLabelled(parts, "flavour likes", document.flavourPreferences().likes());
      addLabelled(parts, "flavour dislikes", document.flavourPreferences().dislikes());
      addNote(parts, "flavour notes", document.flavourPreferences().notes());
    }
    if (document.texturePreferences() != null) {
      addLabelled(parts, "texture likes", document.texturePreferences().likes());
      addLabelled(parts, "texture dislikes", document.texturePreferences().dislikes());
    }
    if (document.ingredientPreferences() != null) {
      addItems(parts, "ingredient favourites", document.ingredientPreferences().favourites());
      addItems(parts, "ingredient disliked", document.ingredientPreferences().disliked());
      addTrending(
          parts, "ingredient trending up", document.ingredientPreferences().trendingPositive());
      addTrending(
          parts, "ingredient trending down", document.ingredientPreferences().trendingNegative());
    }
    if (document.cuisinePreferences() != null) {
      addLabelled(parts, "cuisine favourites", document.cuisinePreferences().favourites());
      addLabelled(parts, "cuisine enjoys", document.cuisinePreferences().enjoys());
      addLabelled(parts, "cuisine less preferred", document.cuisinePreferences().lessPreferred());
      addNote(parts, "cuisine notes", document.cuisinePreferences().notes());
    }
    if (document.cookingPreferences() != null) {
      addLabelled(parts, "preferred methods", document.cookingPreferences().preferredMethods());
      addLabelled(parts, "disliked methods", document.cookingPreferences().dislikedMethods());
    }
    if (document.portionStyle() != null) {
      addNote(parts, "portion preference", document.portionStyle().preference());
      addNote(parts, "salad meals", document.portionStyle().saladMeals());
    }
    if (document.softConstraints() != null && document.softConstraints().intolerances() != null) {
      List<String> intolerances =
          document.softConstraints().intolerances().stream()
              .filter(i -> i != null && i.substance() != null && !i.substance().isBlank())
              .map(SoftIntolerance::substance)
              .toList();
      addLabelled(parts, "soft intolerances", intolerances);
    }
    if (document.householdContext() != null) {
      addLabelled(
          parts,
          "individual-only preferences",
          document.householdContext().individualOnlyPreferences());
      addNote(parts, "household notes", document.householdContext().householdSuitableNotes());
    }
    if (document.activeExperiments() != null && !document.activeExperiments().isEmpty()) {
      List<String> hypotheses =
          document.activeExperiments().stream()
              .filter(e -> e != null && e.hypothesis() != null && !e.hypothesis().isBlank())
              .map(ActiveExperiment::hypothesis)
              .toList();
      addLabelled(parts, "active experiments", hypotheses);
    }
    addLabelled(parts, "learned insights", document.learnedInsights());

    // Skill level is a modifier, not a standalone taste signal: emit it only when the profile
    // carries at least one real preference. This keeps a freshly-initialised profile (whose only
    // populated field is the default skill level) composing to EMPTY, so the listener leaves it
    // PENDING at cold start rather than burning an embed on near-zero signal.
    if (!parts.isEmpty()
        && document.cookingPreferences() != null
        && document.cookingPreferences().skillLevel() != null) {
      parts.add("skill level: " + document.cookingPreferences().skillLevel().name());
    }

    return String.join(". ", parts).trim().replaceAll("\\s+", " ");
  }

  private static void addLabelled(List<String> parts, String label, List<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    List<String> clean = values.stream().filter(v -> v != null && !v.isBlank()).toList();
    if (!clean.isEmpty()) {
      parts.add(label + ": " + String.join(", ", clean));
    }
  }

  private static void addItems(List<String> parts, String label, List<IngredientPreference> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    List<String> names =
        items.stream()
            .filter(i -> i != null && i.item() != null && !i.item().isBlank())
            .map(IngredientPreference::item)
            .toList();
    addLabelled(parts, label, names);
  }

  private static void addTrending(
      List<String> parts, String label, List<TrendingIngredient> items) {
    if (items == null || items.isEmpty()) {
      return;
    }
    List<String> names =
        items.stream()
            .filter(i -> i != null && i.item() != null && !i.item().isBlank())
            .map(TrendingIngredient::item)
            .toList();
    addLabelled(parts, label, names);
  }

  private static void addNote(List<String> parts, String label, String note) {
    if (note != null && !note.isBlank()) {
      parts.add(label + ": " + note.trim());
    }
  }
}
