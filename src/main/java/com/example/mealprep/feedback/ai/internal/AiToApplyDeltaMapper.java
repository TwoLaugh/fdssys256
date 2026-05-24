package com.example.mealprep.feedback.ai.internal;

import com.example.mealprep.feedback.ai.dto.AiTasteProfileDelta;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.IngredientPreferenceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts the AI-response delta shape ({@link AiTasteProfileDelta}, 8 permits, the prompt's field
 * set) into the canonical wire/apply shape ({@link TasteProfileDelta}) consumed by the 01f applier.
 * This is the load-bearing seam between the generate side (preference-01g) and the apply side
 * (preference-01f); it lives in one well-tested function per the ticket §4.
 *
 * <p>Two transforms happen here:
 *
 * <ol>
 *   <li><b>Path vocabulary.</b> The prompt speaks {@code likes.ingredients} / {@code
 *       dislikes.cuisines} / {@code likes.flavour_notes}; the applier resolves {@code
 *       TasteProfileDocument} dotted paths ({@code ingredientPreferences.favourites}, {@code
 *       cuisinePreferences.favourites}, {@code flavourPreferences.likes}). {@link #toDocumentPath}
 *       translates; an unknown prompt path is dropped (logged) rather than fed to the applier as an
 *       invalid path that would reject the whole batch.
 *   <li><b>Payload synthesis.</b> An AI {@code Add} to an object-list path (ingredients) becomes a
 *       wire {@code Add} carrying a synthesised {@code IngredientPreference} JSON ({@code item},
 *       {@code evidenceCount:1}, {@code lastSignal:today}, {@code source:FEEDBACK}); a string-list
 *       path becomes a text-node item. The AI's {@code reasoning}/{@code evidenceFeedbackId}/{@code
 *       confidence} are dropped from the apply payload (recorded in the response's {@code
 *       overallReasoning} → version snapshot context).
 * </ol>
 */
@Component
public class AiToApplyDeltaMapper {

  private static final Logger log = LoggerFactory.getLogger(AiToApplyDeltaMapper.class);

  /**
   * Prompt path → {@code TasteProfileDocument} path. The prompt's {@code likes.*} / {@code
   * dislikes.*} vocabulary maps onto the document's section-keyed lists. {@code cooking_methods} /
   * {@code flavour_notes} use underscored prompt tokens.
   */
  private static final Map<String, String> PATH_VOCABULARY =
      Map.ofEntries(
          Map.entry("likes.ingredients", "ingredientPreferences.favourites"),
          Map.entry("dislikes.ingredients", "ingredientPreferences.disliked"),
          Map.entry("likes.cuisines", "cuisinePreferences.favourites"),
          Map.entry("dislikes.cuisines", "cuisinePreferences.lessPreferred"),
          Map.entry("likes.cooking_methods", "cookingPreferences.preferredMethods"),
          Map.entry("dislikes.cooking_methods", "cookingPreferences.dislikedMethods"),
          Map.entry("likes.flavour_notes", "flavourPreferences.likes"),
          Map.entry("dislikes.flavour_notes", "flavourPreferences.dislikes"));

  /** Object-list document paths whose Add payload is a typed {@code IngredientPreference}. */
  private static final java.util.Set<String> INGREDIENT_OBJECT_PATHS =
      java.util.Set.of("ingredientPreferences.favourites", "ingredientPreferences.disliked");

  /** Notes paths the document supports for {@code UpdateNotes}. */
  private static final Map<String, String> NOTES_PATH_VOCABULARY =
      Map.of(
          "likes.flavour_notes", "flavourPreferences.notes",
          "flavour_notes", "flavourPreferences.notes",
          "cuisines", "cuisinePreferences.notes",
          "cuisine_notes", "cuisinePreferences.notes");

  private final ObjectMapper objectMapper;
  private final Clock clock;

  public AiToApplyDeltaMapper(ObjectMapper objectMapper, Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Maps a list of AI deltas to wire deltas, dropping any whose prompt path cannot be resolved (a
   * dropped op is logged but does not fail the batch). Order is preserved.
   */
  public List<TasteProfileDelta> toApplyDeltas(List<AiTasteProfileDelta> aiDeltas) {
    if (aiDeltas == null || aiDeltas.isEmpty()) {
      return List.of();
    }
    List<TasteProfileDelta> result = new ArrayList<>(aiDeltas.size());
    for (AiTasteProfileDelta ai : aiDeltas) {
      TasteProfileDelta mapped = mapOne(ai);
      if (mapped != null) {
        result.add(mapped);
      }
    }
    return result;
  }

  private TasteProfileDelta mapOne(AiTasteProfileDelta ai) {
    if (ai instanceof AiTasteProfileDelta.Add add) {
      String docPath = toDocumentPath(add.fieldPath());
      if (docPath == null) {
        return dropped(ai, add.fieldPath());
      }
      return new TasteProfileDelta.Add(docPath, addItemPayload(docPath, add));
    } else if (ai instanceof AiTasteProfileDelta.Remove remove) {
      String docPath = toDocumentPath(remove.fieldPath());
      if (docPath == null) {
        return dropped(ai, remove.fieldPath());
      }
      return new TasteProfileDelta.Remove(docPath, remove.item());
    } else if (ai instanceof AiTasteProfileDelta.Update update) {
      String docPath = toDocumentPath(update.fieldPath());
      if (docPath == null) {
        return dropped(ai, update.fieldPath());
      }
      return new TasteProfileDelta.Update(docPath, update.item(), notesPatch(update.newNotes()));
    } else if (ai instanceof AiTasteProfileDelta.Archive archive) {
      String docPath = toDocumentPath(archive.fieldPath());
      if (docPath == null) {
        return dropped(ai, archive.fieldPath());
      }
      return new TasteProfileDelta.Archive(docPath, archive.item(), toArchiveReason(archive));
    } else if (ai instanceof AiTasteProfileDelta.RePromote rePromote) {
      String docPath = toDocumentPath(rePromote.fieldPath());
      if (docPath == null) {
        return dropped(ai, rePromote.fieldPath());
      }
      // AI RePromote(archivedItemKey, fieldPath) → wire RePromote(fieldPath, itemKey).
      return new TasteProfileDelta.RePromote(docPath, rePromote.archivedItemKey());
    } else if (ai instanceof AiTasteProfileDelta.PromoteExperiment promote) {
      String docPath = toDocumentPath(promote.fieldPath());
      if (docPath == null) {
        return dropped(ai, promote.fieldPath());
      }
      // AI keys the experiment by hypothesisId text; the applier matches on the hypothesis string.
      return new TasteProfileDelta.PromoteExperiment(
          promote.hypothesisId(), docPath, promotedItemPayload(docPath, promote.item()));
    } else if (ai instanceof AiTasteProfileDelta.DiscardExperiment discard) {
      return new TasteProfileDelta.DiscardExperiment(discard.hypothesisId());
    } else if (ai instanceof AiTasteProfileDelta.UpdateNotes notes) {
      String notesPath = toNotesPath(notes.fieldPath());
      if (notesPath == null) {
        return dropped(ai, notes.fieldPath());
      }
      return new TasteProfileDelta.UpdateNotes(notesPath, notes.newNotes());
    }
    return dropped(ai, "(unknown op)");
  }

  /**
   * Synthesise the wire {@code Add} item payload. Ingredient object-paths get a typed {@code
   * IngredientPreference} JSON ({@code evidenceCount:1}, {@code source:FEEDBACK}, {@code
   * lastSignal:today}); every other (string-list) path gets a bare text node of the item.
   */
  private JsonNode addItemPayload(String docPath, AiTasteProfileDelta.Add add) {
    if (INGREDIENT_OBJECT_PATHS.contains(docPath)) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("item", add.item());
      node.put("evidenceCount", 1);
      node.put("lastSignal", today().toString());
      node.put("source", IngredientPreferenceSource.FEEDBACK.name());
      return node;
    }
    return objectMapper.getNodeFactory().textNode(add.item());
  }

  /**
   * Synthesise the wire {@code PromoteExperiment.promotedItem}. Ingredient object-paths get the
   * same typed {@code IngredientPreference} shape; string-list paths get a text node.
   */
  private JsonNode promotedItemPayload(String docPath, String item) {
    if (INGREDIENT_OBJECT_PATHS.contains(docPath)) {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("item", item);
      node.put("evidenceCount", 1);
      node.put("lastSignal", today().toString());
      node.put("source", IngredientPreferenceSource.FEEDBACK.name());
      return node;
    }
    return objectMapper.getNodeFactory().textNode(item);
  }

  /** Wrap an Update's free-text notes into the wire patch shape ({@code {"notes": "..."}}). */
  private JsonNode notesPatch(String newNotes) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("notes", newNotes);
    return node;
  }

  /**
   * Translate a prompt fieldPath to a {@code TasteProfileDocument} path. A path already in document
   * form (the applier's own vocabulary, e.g. {@code ingredientPreferences.favourites}) passes
   * through unchanged; an unrecognised path returns {@code null} (the op is dropped, not applied as
   * an invalid path).
   */
  String toDocumentPath(String promptPath) {
    if (promptPath == null) {
      return null;
    }
    String mapped = PATH_VOCABULARY.get(promptPath);
    if (mapped != null) {
      return mapped;
    }
    // Already a document path? (the applier's resolvable set is broader than the prompt
    // vocabulary).
    if (promptPath.contains("Preferences.")
        || promptPath.equals("learnedInsights")
        || promptPath.equals("recipesToRepeat")
        || promptPath.equals("recipesToAvoid")
        || promptPath.equals("activeExperiments")) {
      return promptPath;
    }
    return null;
  }

  /** Translate a prompt notes fieldPath to a document notes path; null if unrecognised. */
  String toNotesPath(String promptPath) {
    if (promptPath == null) {
      return null;
    }
    if (promptPath.equals("flavourPreferences.notes")
        || promptPath.equals("cuisinePreferences.notes")) {
      return promptPath;
    }
    return NOTES_PATH_VOCABULARY.get(promptPath);
  }

  /**
   * Map the AI's free-text archive reason to a wire {@link ArchiveReason} enum. The AI's reason is
   * a justification ("user has gone off coriander"), which does not fit the eviction-flavoured enum
   * ({@code LOW_EVIDENCE}/{@code STALE}/{@code TOKEN_PRESSURE}); a user moving away from a
   * preference is closest to {@code STALE}. The free-text reason survives in the response's {@code
   * overallReasoning} → version snapshot. An exact enum-name match (if the model ever returns one)
   * is honoured.
   */
  private String toArchiveReason(AiTasteProfileDelta.Archive archive) {
    String raw = archive.archiveReason();
    if (raw != null) {
      for (ArchiveReason r : ArchiveReason.values()) {
        if (r.name().equalsIgnoreCase(raw.trim())) {
          return r.name();
        }
      }
    }
    return ArchiveReason.STALE.name();
  }

  private TasteProfileDelta dropped(AiTasteProfileDelta ai, String path) {
    log.warn(
        "dropping AI taste-profile delta with unresolvable fieldPath — op={} promptPath={}",
        ai.getClass().getSimpleName(),
        path);
    return null;
  }

  private LocalDate today() {
    return LocalDate.ofInstant(Objects.requireNonNull(clock.instant()), ZoneOffset.UTC);
  }
}
