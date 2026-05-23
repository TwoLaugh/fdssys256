package com.example.mealprep.preference.domain.service.internal;

import com.example.mealprep.preference.api.dto.ApplyTasteProfileDeltasRequest;
import com.example.mealprep.preference.api.dto.ArchiveItemRequest;
import com.example.mealprep.preference.api.dto.PreferenceArchiveEntryDto;
import com.example.mealprep.preference.api.dto.TasteProfileDelta;
import com.example.mealprep.preference.domain.document.TasteProfileDocument;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.ActiveExperiment;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CookingPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.CuisinePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.FlavourPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreference;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.IngredientPreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.RecipeRecommendation;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TexturePreferences;
import com.example.mealprep.preference.domain.document.TasteProfileDocument.TrendingIngredient;
import com.example.mealprep.preference.domain.entity.ArchiveReason;
import com.example.mealprep.preference.domain.entity.ExperimentStatus;
import com.example.mealprep.preference.domain.service.PreferenceArchiveUpdateService;
import com.example.mealprep.preference.exception.InvalidTasteProfileDeltaException;
import com.example.mealprep.preference.exception.PreferenceArchiveEntryNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies a batch of {@code TasteProfileDelta} operations to a {@link TasteProfileDocument}.
 *
 * <p>The applier is a <b>pure document transformer plus an archive side-effect</b> (preference-01f,
 * {@code lld/preference.md} Flow 3). It does NOT touch the {@code TasteProfile} entity — the
 * service loads/saves that and bumps the entity-level {@code documentVersion}. The applier:
 *
 * <ol>
 *   <li>runs a whole-batch <b>validate</b> pass (field paths resolve, targets exist, experiment
 *       hypotheses match, ≤50 ops, ≤1 {@code UpdateNotes}); any failure → {@link
 *       InvalidTasteProfileDeltaException} (422) and nothing is applied;
 *   <li><b>applies</b> the ops in order, each producing a new immutable {@link
 *       TasteProfileDocument} (copy-and-replace at the targeted path);
 *   <li>drives the <b>archive</b> for {@code Archive} ({@code archiveItem}) and {@code RePromote}
 *       ({@code markRePromoted} + verbatim restore — the C-IMP-007 re-emergence path) ops, both
 *       joining the service's single transaction;
 *   <li>logs a structured <b>anomaly WARN</b> when a batch removes/archives &gt;3 items;
 *   <li>stamps the returned document's internal {@code version}, {@code lastUpdated}, {@code
 *       basedOnFeedbackCount} and {@code feedbackCursor} from the request's feedback range.
 * </ol>
 */
public interface TasteProfileDeltaApplier {

  /**
   * Applies the deltas in {@code request} to {@code current} and returns the new document. Bumps
   * the document's internal {@code version} field and updates {@code lastUpdated} / {@code
   * basedOnFeedbackCount} / {@code feedbackCursor} as a side-effect encoded in the return value.
   *
   * <p>{@code userId} is required because the {@code Archive} / {@code RePromote} ops drive {@code
   * PreferenceArchiveUpdateService}, whose rows are keyed on the user. It is NOT carried on {@code
   * request} (which only holds the {@code feedback-<id>} trace), so the service threads it in
   * explicitly — a deviation from the original 01c stub signature, see ticket §2.
   *
   * @throws InvalidTasteProfileDeltaException if any delta in the batch fails validation (422); the
   *     batch is rejected whole and {@code current} is unchanged.
   */
  TasteProfileDocument apply(
      TasteProfileDocument current, ApplyTasteProfileDeltasRequest request, UUID userId);

  /**
   * Deterministic delta applier (preference-01f). Replaces the 01c {@code NoopStub}. The
   * {@code @Component} lookup is unchanged so {@code TasteProfileServiceImpl}'s constructor
   * injection of the {@link TasteProfileDeltaApplier} interface is untouched.
   */
  @Component
  class Default implements TasteProfileDeltaApplier {

    private static final Logger log = LoggerFactory.getLogger(Default.class);

    /** Max ops per batch — also enforced by Jakarta {@code @Size(max=50)} on the DTO. */
    private static final int MAX_DELTAS = 50;

    /** Threshold above which a removal-heavy batch is flagged as a possible bad update. */
    private static final int ANOMALY_REMOVAL_THRESHOLD = 3;

    /** String-list collection paths (the item itself is the key). */
    private static final Set<String> STRING_LIST_PATHS =
        Set.of(
            "flavourPreferences.likes",
            "flavourPreferences.dislikes",
            "texturePreferences.likes",
            "texturePreferences.dislikes",
            "cuisinePreferences.favourites",
            "cuisinePreferences.enjoys",
            "cuisinePreferences.lessPreferred",
            "cookingPreferences.preferredMethods",
            "cookingPreferences.dislikedMethods",
            "learnedInsights");

    /** Object-list collection paths (each element has a typed dedup key). */
    private static final Set<String> OBJECT_LIST_PATHS =
        Set.of(
            "ingredientPreferences.favourites",
            "ingredientPreferences.disliked",
            "ingredientPreferences.trendingPositive",
            "ingredientPreferences.trendingNegative",
            "recipesToRepeat",
            "recipesToAvoid",
            "activeExperiments");

    /** Free-text notes paths targetable by {@code UpdateNotes}. */
    private static final Set<String> NOTES_PATHS =
        Set.of("flavourPreferences.notes", "cuisinePreferences.notes");

    private final ObjectMapper objectMapper;
    private final PreferenceArchiveUpdateService archiveUpdateService;
    private final Clock clock;

    public Default(
        ObjectMapper objectMapper,
        PreferenceArchiveUpdateService archiveUpdateService,
        Clock clock) {
      this.objectMapper = objectMapper;
      this.archiveUpdateService = archiveUpdateService;
      this.clock = clock;
    }

    @Override
    public TasteProfileDocument apply(
        TasteProfileDocument current, ApplyTasteProfileDeltasRequest request, UUID userId) {
      List<TasteProfileDelta> deltas = request.deltas() == null ? List.of() : request.deltas();
      // Whole-batch validate first — any failure rejects the batch with no partial application.
      validate(current, deltas);

      LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
      TasteProfileDocument doc = current;
      int removalCount = 0;
      for (TasteProfileDelta delta : deltas) {
        doc = applyOne(doc, delta, userId);
        if (delta instanceof TasteProfileDelta.Remove
            || delta instanceof TasteProfileDelta.Archive) {
          removalCount++;
        }
      }

      if (removalCount > ANOMALY_REMOVAL_THRESHOLD) {
        log.warn(
            "taste-profile delta anomaly: removal-heavy batch userId={} feedbackRange={}..{} "
                + "removeOrArchiveCount={} totalDeltas={} ops={}",
            userId,
            request.feedbackRangeStart(),
            request.feedbackRangeEnd(),
            removalCount,
            deltas.size(),
            opKinds(deltas));
      }

      // Stamp the document-level provenance fields. version + lastUpdated are bumped here; the
      // entity-level documentVersion is bumped in lock-step by the service.
      int feedbackCount = current.basedOnFeedbackCount() + deltas.size();
      return new TasteProfileDocument(
          today,
          current.version() + 1,
          feedbackCount,
          request.feedbackRangeEnd() == null ? doc.feedbackCursor() : request.feedbackRangeEnd(),
          doc.softConstraints(),
          doc.flavourPreferences(),
          doc.texturePreferences(),
          doc.ingredientPreferences(),
          doc.cuisinePreferences(),
          doc.cookingPreferences(),
          doc.portionStyle(),
          doc.householdContext(),
          doc.recipesToRepeat(),
          doc.recipesToAvoid(),
          doc.activeExperiments(),
          doc.learnedInsights());
    }

    // ---------------- validation ----------------

    /**
     * Whole-batch validation. Walks a <b>running, simulated</b> document (pure transforms only — no
     * archive side-effects) so existence checks account for items created earlier in the same batch
     * (e.g. Add-then-Remove of the same key validates). Any failure throws before the real apply
     * pass runs, so no archive write ever happens for a batch containing an invalid op.
     */
    private void validate(TasteProfileDocument current, List<TasteProfileDelta> deltas) {
      if (deltas.size() > MAX_DELTAS) {
        throw new InvalidTasteProfileDeltaException(
            "delta batch exceeds max of " + MAX_DELTAS + ": " + deltas.size());
      }
      int updateNotesCount = 0;
      TasteProfileDocument doc = current;
      for (TasteProfileDelta delta : deltas) {
        if (delta instanceof TasteProfileDelta.UpdateNotes) {
          updateNotesCount++;
        }
        doc = validateAndSimulate(doc, delta);
      }
      if (updateNotesCount > 1) {
        throw new InvalidTasteProfileDeltaException(
            "at most one UpdateNotes op per batch, found " + updateNotesCount);
      }
    }

    /**
     * Validates {@code delta} against the running {@code doc} and returns the simulated next doc.
     */
    private TasteProfileDocument validateAndSimulate(
        TasteProfileDocument doc, TasteProfileDelta delta) {
      if (delta instanceof TasteProfileDelta.Add add) {
        requireResolvableCollection(add.fieldPath());
        return applyAdd(doc, add.fieldPath(), add.item());
      } else if (delta instanceof TasteProfileDelta.Remove remove) {
        requireResolvableCollection(remove.fieldPath());
        requireItemExists(doc, remove.fieldPath(), remove.itemKey());
        return applyRemove(doc, remove.fieldPath(), remove.itemKey());
      } else if (delta instanceof TasteProfileDelta.Update update) {
        requireResolvableCollection(update.fieldPath());
        requireObjectCollection(update.fieldPath());
        requireItemExists(doc, update.fieldPath(), update.itemKey());
        return applyUpdate(doc, update.fieldPath(), update.itemKey(), update.patch());
      } else if (delta instanceof TasteProfileDelta.UpdateNotes notes) {
        requireNotesPath(notes.fieldPath());
        return applyUpdateNotes(doc, notes.fieldPath(), notes.notes());
      } else if (delta instanceof TasteProfileDelta.PromoteExperiment promote) {
        requireResolvableCollection(promote.targetFieldPath());
        requireActiveExperiment(doc, promote.hypothesis());
        return applyPromoteExperiment(doc, promote);
      } else if (delta instanceof TasteProfileDelta.DiscardExperiment discard) {
        requireActiveExperiment(doc, discard.hypothesis());
        return applyDiscardExperiment(doc, discard.hypothesis());
      } else if (delta instanceof TasteProfileDelta.Archive archive) {
        requireResolvableCollection(archive.fieldPath());
        requireItemExists(doc, archive.fieldPath(), archive.itemKey());
        requireArchiveReason(archive.reason());
        // Simulate the document removal only — no archive write during validation.
        return applyRemove(doc, archive.fieldPath(), archive.itemKey());
      } else if (delta instanceof TasteProfileDelta.RePromote rePromote) {
        // RePromote is validated lazily at apply time: a missing archive entry falls back to Add
        // (warn-log), so it is NOT a batch-rejecting failure. The fieldPath must still resolve.
        // Simulate the restore as an Add of a key stub so later ops in the batch see it present.
        requireResolvableCollection(rePromote.fieldPath());
        return applyAdd(doc, rePromote.fieldPath(), null, rePromote.itemKey());
      }
      throw new InvalidTasteProfileDeltaException(
          "unknown delta type: " + delta.getClass().getSimpleName());
    }

    private void requireResolvableCollection(String fieldPath) {
      if (!STRING_LIST_PATHS.contains(fieldPath) && !OBJECT_LIST_PATHS.contains(fieldPath)) {
        throw new InvalidTasteProfileDeltaException(
            "unknown taste-profile fieldPath: " + fieldPath);
      }
    }

    private void requireObjectCollection(String fieldPath) {
      if (!OBJECT_LIST_PATHS.contains(fieldPath)) {
        throw new InvalidTasteProfileDeltaException(
            "Update targets a string-list path which has no patchable fields: " + fieldPath);
      }
    }

    private void requireNotesPath(String fieldPath) {
      if (!NOTES_PATHS.contains(fieldPath)) {
        throw new InvalidTasteProfileDeltaException("unknown notes fieldPath: " + fieldPath);
      }
    }

    private void requireItemExists(TasteProfileDocument doc, String fieldPath, String itemKey) {
      if (!containsKey(doc, fieldPath, itemKey)) {
        throw new InvalidTasteProfileDeltaException(
            "no item with key '" + itemKey + "' at fieldPath '" + fieldPath + "'");
      }
    }

    private void requireActiveExperiment(TasteProfileDocument doc, String hypothesis) {
      boolean match =
          doc.activeExperiments().stream()
              .anyMatch(
                  e ->
                      Objects.equals(e.hypothesis(), hypothesis)
                          && e.status() == ExperimentStatus.TESTING);
      if (!match) {
        throw new InvalidTasteProfileDeltaException(
            "no active TESTING experiment matching hypothesis '" + hypothesis + "'");
      }
    }

    private void requireArchiveReason(String reason) {
      try {
        ArchiveReason.valueOf(reason);
      } catch (IllegalArgumentException ex) {
        throw new InvalidTasteProfileDeltaException("unknown archive reason: " + reason, ex);
      }
    }

    // ---------------- per-op apply ----------------

    private TasteProfileDocument applyOne(
        TasteProfileDocument doc, TasteProfileDelta delta, UUID userId) {
      if (delta instanceof TasteProfileDelta.Add add) {
        return applyAdd(doc, add.fieldPath(), add.item());
      } else if (delta instanceof TasteProfileDelta.Remove remove) {
        return applyRemove(doc, remove.fieldPath(), remove.itemKey());
      } else if (delta instanceof TasteProfileDelta.Update update) {
        return applyUpdate(doc, update.fieldPath(), update.itemKey(), update.patch());
      } else if (delta instanceof TasteProfileDelta.UpdateNotes notes) {
        return applyUpdateNotes(doc, notes.fieldPath(), notes.notes());
      } else if (delta instanceof TasteProfileDelta.PromoteExperiment promote) {
        return applyPromoteExperiment(doc, promote);
      } else if (delta instanceof TasteProfileDelta.DiscardExperiment discard) {
        return applyDiscardExperiment(doc, discard.hypothesis());
      } else if (delta instanceof TasteProfileDelta.Archive archive) {
        return applyArchive(doc, archive, userId);
      } else if (delta instanceof TasteProfileDelta.RePromote rePromote) {
        return applyRePromote(doc, rePromote, userId);
      }
      throw new InvalidTasteProfileDeltaException(
          "unknown delta type: " + delta.getClass().getSimpleName());
    }

    private TasteProfileDocument applyAdd(
        TasteProfileDocument doc, String fieldPath, JsonNode item) {
      if (STRING_LIST_PATHS.contains(fieldPath)) {
        String value = item == null ? null : item.asText(null);
        if (value == null || value.isBlank()) {
          throw new InvalidTasteProfileDeltaException(
              "Add to string-list path '" + fieldPath + "' requires a non-blank text item");
        }
        return withStringList(doc, fieldPath, list -> appendStringDedup(list, value));
      }
      Object parsed = parseItem(fieldPath, item);
      return withObjectList(doc, fieldPath, list -> appendObjectDedup(fieldPath, list, parsed));
    }

    private TasteProfileDocument applyRemove(
        TasteProfileDocument doc, String fieldPath, String itemKey) {
      if (STRING_LIST_PATHS.contains(fieldPath)) {
        return withStringList(
            doc, fieldPath, list -> list.stream().filter(s -> !s.equals(itemKey)).toList());
      }
      return withObjectList(
          doc,
          fieldPath,
          list -> list.stream().filter(o -> !keyOf(fieldPath, o).equals(itemKey)).toList());
    }

    private TasteProfileDocument applyUpdate(
        TasteProfileDocument doc, String fieldPath, String itemKey, JsonNode patch) {
      return withObjectList(
          doc,
          fieldPath,
          list -> {
            List<Object> result = new ArrayList<>(list.size());
            for (Object existing : list) {
              if (keyOf(fieldPath, existing).equals(itemKey)) {
                result.add(patchItem(fieldPath, existing, patch));
              } else {
                result.add(existing);
              }
            }
            return result;
          });
    }

    private TasteProfileDocument applyUpdateNotes(
        TasteProfileDocument doc, String fieldPath, String notes) {
      return switch (fieldPath) {
        case "flavourPreferences.notes" -> {
          FlavourPreferences f = doc.flavourPreferences();
          yield withDoc(doc)
              .flavour(new FlavourPreferences(f.likes(), f.dislikes(), notes))
              .build();
        }
        case "cuisinePreferences.notes" -> {
          CuisinePreferences c = doc.cuisinePreferences();
          yield withDoc(doc)
              .cuisine(new CuisinePreferences(c.favourites(), c.enjoys(), c.lessPreferred(), notes))
              .build();
        }
        default ->
            throw new InvalidTasteProfileDeltaException("unknown notes fieldPath: " + fieldPath);
      };
    }

    private TasteProfileDocument applyPromoteExperiment(
        TasteProfileDocument doc, TasteProfileDelta.PromoteExperiment promote) {
      // Flip the matching experiment to PROMOTED, then add promotedItem to its target collection.
      List<ActiveExperiment> experiments =
          doc.activeExperiments().stream()
              .map(
                  e ->
                      Objects.equals(e.hypothesis(), promote.hypothesis())
                              && e.status() == ExperimentStatus.TESTING
                          ? new ActiveExperiment(
                              e.hypothesis(),
                              TasteProfileDelta.defaultPromotedStatus(),
                              e.evidenceFor(),
                              e.evidenceAgainst(),
                              e.created())
                          : e)
              .toList();
      TasteProfileDocument promoted = withDoc(doc).experiments(experiments).build();
      return applyAdd(promoted, promote.targetFieldPath(), promote.promotedItem());
    }

    private TasteProfileDocument applyDiscardExperiment(
        TasteProfileDocument doc, String hypothesis) {
      // Retain the experiment in the list with status DISCARDED (auditability).
      List<ActiveExperiment> experiments =
          doc.activeExperiments().stream()
              .map(
                  e ->
                      Objects.equals(e.hypothesis(), hypothesis)
                              && e.status() == ExperimentStatus.TESTING
                          ? new ActiveExperiment(
                              e.hypothesis(),
                              ExperimentStatus.DISCARDED,
                              e.evidenceFor(),
                              e.evidenceAgainst(),
                              e.created())
                          : e)
              .toList();
      return withDoc(doc).experiments(experiments).build();
    }

    private TasteProfileDocument applyArchive(
        TasteProfileDocument doc, TasteProfileDelta.Archive archive, UUID userId) {
      // Capture the live item's full JSON BEFORE removing it, so the archive preserves it verbatim.
      JsonNode payload;
      int evidenceCount;
      LocalDate lastSignal;
      if (STRING_LIST_PATHS.contains(archive.fieldPath())) {
        // String-list item: the value itself is the payload; no evidence / signal metadata exists.
        payload = objectMapper.getNodeFactory().textNode(archive.itemKey());
        evidenceCount = 0;
        lastSignal = null;
      } else {
        Object item = findObject(doc, archive.fieldPath(), archive.itemKey());
        payload = objectMapper.valueToTree(item);
        evidenceCount = evidenceCountOf(item);
        lastSignal = lastSignalOf(item);
      }

      archiveUpdateService.archiveItem(
          userId,
          new ArchiveItemRequest(
              archive.fieldPath(),
              archive.itemKey(),
              payload,
              evidenceCount,
              lastSignal,
              ArchiveReason.valueOf(archive.reason())));

      return applyRemove(doc, archive.fieldPath(), archive.itemKey());
    }

    private TasteProfileDocument applyRePromote(
        TasteProfileDocument doc, TasteProfileDelta.RePromote rePromote, UUID userId) {
      PreferenceArchiveEntryDto restored;
      try {
        restored =
            archiveUpdateService.markRePromoted(userId, rePromote.fieldPath(), rePromote.itemKey());
      } catch (PreferenceArchiveEntryNotFoundException notFound) {
        // No matching unpromoted archive entry — fall back to a fresh Add of just the key. The
        // batch is NOT rejected (lld/preference.md:660,723).
        log.warn(
            "re-promote with no matching archive entry — falling back to Add. userId={} "
                + "fieldPath={} itemKey={}",
            userId,
            rePromote.fieldPath(),
            rePromote.itemKey());
        return applyAdd(doc, rePromote.fieldPath(), null /* string-list */, rePromote.itemKey());
      }
      // Restore the archived payload verbatim into the live collection (evidence preserved).
      return applyAdd(doc, rePromote.fieldPath(), restored.itemPayload());
    }

    /**
     * Add overload for the RePromote fallback: string-list paths add the key text; object paths
     * parse a minimal stub from the key.
     */
    private TasteProfileDocument applyAdd(
        TasteProfileDocument doc, String fieldPath, JsonNode unused, String key) {
      if (STRING_LIST_PATHS.contains(fieldPath)) {
        return withStringList(doc, fieldPath, list -> appendStringDedup(list, key));
      }
      // Object collections: synthesise a minimal payload keyed on the dedup field so the Add is
      // well-typed. Evidence is unknown (the archive lookup missed) → defaults.
      JsonNode stub = stubFor(fieldPath, key);
      Object parsed = parseItem(fieldPath, stub);
      return withObjectList(doc, fieldPath, list -> appendObjectDedup(fieldPath, list, parsed));
    }

    // ---------------- collection plumbing ----------------

    private boolean containsKey(TasteProfileDocument doc, String fieldPath, String itemKey) {
      if (STRING_LIST_PATHS.contains(fieldPath)) {
        return stringList(doc, fieldPath).contains(itemKey);
      }
      return objectList(doc, fieldPath).stream().anyMatch(o -> keyOf(fieldPath, o).equals(itemKey));
    }

    private Object findObject(TasteProfileDocument doc, String fieldPath, String itemKey) {
      return objectList(doc, fieldPath).stream()
          .filter(o -> keyOf(fieldPath, o).equals(itemKey))
          .findFirst()
          .orElseThrow(
              () ->
                  new InvalidTasteProfileDeltaException(
                      "no item with key '" + itemKey + "' at fieldPath '" + fieldPath + "'"));
    }

    private List<String> stringList(TasteProfileDocument doc, String fieldPath) {
      return switch (fieldPath) {
        case "flavourPreferences.likes" -> doc.flavourPreferences().likes();
        case "flavourPreferences.dislikes" -> doc.flavourPreferences().dislikes();
        case "texturePreferences.likes" -> doc.texturePreferences().likes();
        case "texturePreferences.dislikes" -> doc.texturePreferences().dislikes();
        case "cuisinePreferences.favourites" -> doc.cuisinePreferences().favourites();
        case "cuisinePreferences.enjoys" -> doc.cuisinePreferences().enjoys();
        case "cuisinePreferences.lessPreferred" -> doc.cuisinePreferences().lessPreferred();
        case "cookingPreferences.preferredMethods" -> doc.cookingPreferences().preferredMethods();
        case "cookingPreferences.dislikedMethods" -> doc.cookingPreferences().dislikedMethods();
        case "learnedInsights" -> doc.learnedInsights();
        default ->
            throw new InvalidTasteProfileDeltaException(
                "not a string-list fieldPath: " + fieldPath);
      };
    }

    @SuppressWarnings("unchecked")
    private List<Object> objectList(TasteProfileDocument doc, String fieldPath) {
      List<?> list =
          switch (fieldPath) {
            case "ingredientPreferences.favourites" -> doc.ingredientPreferences().favourites();
            case "ingredientPreferences.disliked" -> doc.ingredientPreferences().disliked();
            case "ingredientPreferences.trendingPositive" ->
                doc.ingredientPreferences().trendingPositive();
            case "ingredientPreferences.trendingNegative" ->
                doc.ingredientPreferences().trendingNegative();
            case "recipesToRepeat" -> doc.recipesToRepeat();
            case "recipesToAvoid" -> doc.recipesToAvoid();
            case "activeExperiments" -> doc.activeExperiments();
            default ->
                throw new InvalidTasteProfileDeltaException(
                    "not an object-list fieldPath: " + fieldPath);
          };
      return (List<Object>) list;
    }

    private TasteProfileDocument withStringList(
        TasteProfileDocument doc,
        String fieldPath,
        java.util.function.UnaryOperator<List<String>> op) {
      List<String> updated = op.apply(stringList(doc, fieldPath));
      DocBuilder b = withDoc(doc);
      switch (fieldPath) {
        case "flavourPreferences.likes" -> {
          FlavourPreferences f = doc.flavourPreferences();
          b.flavour(new FlavourPreferences(updated, f.dislikes(), f.notes()));
        }
        case "flavourPreferences.dislikes" -> {
          FlavourPreferences f = doc.flavourPreferences();
          b.flavour(new FlavourPreferences(f.likes(), updated, f.notes()));
        }
        case "texturePreferences.likes" -> {
          TexturePreferences t = doc.texturePreferences();
          b.texture(new TexturePreferences(updated, t.dislikes()));
        }
        case "texturePreferences.dislikes" -> {
          TexturePreferences t = doc.texturePreferences();
          b.texture(new TexturePreferences(t.likes(), updated));
        }
        case "cuisinePreferences.favourites" -> {
          CuisinePreferences c = doc.cuisinePreferences();
          b.cuisine(new CuisinePreferences(updated, c.enjoys(), c.lessPreferred(), c.notes()));
        }
        case "cuisinePreferences.enjoys" -> {
          CuisinePreferences c = doc.cuisinePreferences();
          b.cuisine(new CuisinePreferences(c.favourites(), updated, c.lessPreferred(), c.notes()));
        }
        case "cuisinePreferences.lessPreferred" -> {
          CuisinePreferences c = doc.cuisinePreferences();
          b.cuisine(new CuisinePreferences(c.favourites(), c.enjoys(), updated, c.notes()));
        }
        case "cookingPreferences.preferredMethods" -> {
          CookingPreferences ck = doc.cookingPreferences();
          b.cooking(new CookingPreferences(ck.skillLevel(), updated, ck.dislikedMethods()));
        }
        case "cookingPreferences.dislikedMethods" -> {
          CookingPreferences ck = doc.cookingPreferences();
          b.cooking(new CookingPreferences(ck.skillLevel(), ck.preferredMethods(), updated));
        }
        case "learnedInsights" -> b.insights(updated);
        default ->
            throw new InvalidTasteProfileDeltaException(
                "not a string-list fieldPath: " + fieldPath);
      }
      return b.build();
    }

    @SuppressWarnings("unchecked")
    private TasteProfileDocument withObjectList(
        TasteProfileDocument doc,
        String fieldPath,
        java.util.function.UnaryOperator<List<Object>> op) {
      List<Object> updated = op.apply(objectList(doc, fieldPath));
      DocBuilder b = withDoc(doc);
      IngredientPreferences ip = doc.ingredientPreferences();
      switch (fieldPath) {
        case "ingredientPreferences.favourites" ->
            b.ingredients(
                new IngredientPreferences(
                    (List<IngredientPreference>) (List<?>) updated,
                    ip.disliked(),
                    ip.trendingPositive(),
                    ip.trendingNegative()));
        case "ingredientPreferences.disliked" ->
            b.ingredients(
                new IngredientPreferences(
                    ip.favourites(),
                    (List<IngredientPreference>) (List<?>) updated,
                    ip.trendingPositive(),
                    ip.trendingNegative()));
        case "ingredientPreferences.trendingPositive" ->
            b.ingredients(
                new IngredientPreferences(
                    ip.favourites(),
                    ip.disliked(),
                    (List<TrendingIngredient>) (List<?>) updated,
                    ip.trendingNegative()));
        case "ingredientPreferences.trendingNegative" ->
            b.ingredients(
                new IngredientPreferences(
                    ip.favourites(),
                    ip.disliked(),
                    ip.trendingPositive(),
                    (List<TrendingIngredient>) (List<?>) updated));
        case "recipesToRepeat" -> b.recipesToRepeat((List<RecipeRecommendation>) (List<?>) updated);
        case "recipesToAvoid" -> b.recipesToAvoid((List<RecipeRecommendation>) (List<?>) updated);
        case "activeExperiments" -> b.experiments((List<ActiveExperiment>) (List<?>) updated);
        default ->
            throw new InvalidTasteProfileDeltaException(
                "not an object-list fieldPath: " + fieldPath);
      }
      return b.build();
    }

    // ---------------- item identity, parse, patch ----------------

    /** The dedup identity of an object item within its collection. */
    private String keyOf(String fieldPath, Object item) {
      if (item instanceof IngredientPreference ingredient) {
        return ingredient.item();
      } else if (item instanceof TrendingIngredient trending) {
        return trending.item();
      } else if (item instanceof RecipeRecommendation recipe) {
        return recipe.name();
      } else if (item instanceof ActiveExperiment experiment) {
        return experiment.hypothesis();
      }
      throw new InvalidTasteProfileDeltaException(
          "no key resolver for item at fieldPath '" + fieldPath + "'");
    }

    private Object parseItem(String fieldPath, JsonNode item) {
      if (item == null || item.isNull()) {
        throw new InvalidTasteProfileDeltaException(
            "Add to object path '" + fieldPath + "' requires an item payload");
      }
      try {
        return objectMapper.treeToValue(item, itemTypeFor(fieldPath));
      } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new InvalidTasteProfileDeltaException(
            "could not parse item for fieldPath '" + fieldPath + "': " + ex.getMessage(), ex);
      }
    }

    private Object patchItem(String fieldPath, Object existing, JsonNode patch) {
      if (patch == null || patch.isNull()) {
        return existing;
      }
      try {
        JsonNode merged = objectMapper.valueToTree(existing);
        // Shallow field merge: overlay the patch's fields onto the existing item's JSON tree, then
        // re-parse. The identity key field is left in place by the merge (the patch should not
        // change it; if it does we re-assert below).
        ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
            .setAll((com.fasterxml.jackson.databind.node.ObjectNode) patch);
        Object patched = objectMapper.treeToValue(merged, itemTypeFor(fieldPath));
        if (!keyOf(fieldPath, patched).equals(keyOf(fieldPath, existing))) {
          throw new InvalidTasteProfileDeltaException(
              "Update patch must not change the item identity key at fieldPath '"
                  + fieldPath
                  + "'");
        }
        return patched;
      } catch (ClassCastException ex) {
        throw new InvalidTasteProfileDeltaException(
            "Update patch for fieldPath '" + fieldPath + "' must be a JSON object", ex);
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new InvalidTasteProfileDeltaException(
            "could not apply Update patch at fieldPath '" + fieldPath + "': " + ex.getMessage(),
            ex);
      }
    }

    private Class<?> itemTypeFor(String fieldPath) {
      return switch (fieldPath) {
        case "ingredientPreferences.favourites", "ingredientPreferences.disliked" ->
            IngredientPreference.class;
        case "ingredientPreferences.trendingPositive", "ingredientPreferences.trendingNegative" ->
            TrendingIngredient.class;
        case "recipesToRepeat", "recipesToAvoid" -> RecipeRecommendation.class;
        case "activeExperiments" -> ActiveExperiment.class;
        default ->
            throw new InvalidTasteProfileDeltaException(
                "no item type for fieldPath '" + fieldPath + "'");
      };
    }

    private JsonNode stubFor(String fieldPath, String key) {
      com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
      switch (fieldPath) {
        case "ingredientPreferences.favourites",
                "ingredientPreferences.disliked",
                "ingredientPreferences.trendingPositive",
                "ingredientPreferences.trendingNegative" ->
            node.put("item", key);
        case "recipesToRepeat", "recipesToAvoid" -> node.put("name", key);
        case "activeExperiments" -> node.put("hypothesis", key);
        default ->
            throw new InvalidTasteProfileDeltaException(
                "no stub for fieldPath '" + fieldPath + "'");
      }
      return node;
    }

    private int evidenceCountOf(Object item) {
      if (item instanceof IngredientPreference ingredient) {
        return ingredient.evidenceCount();
      } else if (item instanceof TrendingIngredient trending) {
        return trending.evidenceCount();
      } else if (item instanceof ActiveExperiment experiment) {
        return experiment.evidenceFor();
      }
      return 0;
    }

    private LocalDate lastSignalOf(Object item) {
      if (item instanceof IngredientPreference ingredient) {
        return ingredient.lastSignal();
      } else if (item instanceof TrendingIngredient trending) {
        return trending.firstSignal();
      } else if (item instanceof ActiveExperiment experiment) {
        return experiment.created();
      }
      return null;
    }

    private List<TasteProfileDelta.Kind> opKinds(List<TasteProfileDelta> deltas) {
      return deltas.stream().map(Default::kindOf).toList();
    }

    private static TasteProfileDelta.Kind kindOf(TasteProfileDelta delta) {
      if (delta instanceof TasteProfileDelta.Add) {
        return TasteProfileDelta.Kind.ADD;
      } else if (delta instanceof TasteProfileDelta.Remove) {
        return TasteProfileDelta.Kind.REMOVE;
      } else if (delta instanceof TasteProfileDelta.Update) {
        return TasteProfileDelta.Kind.UPDATE;
      } else if (delta instanceof TasteProfileDelta.UpdateNotes) {
        return TasteProfileDelta.Kind.UPDATE_NOTES;
      } else if (delta instanceof TasteProfileDelta.PromoteExperiment) {
        return TasteProfileDelta.Kind.PROMOTE_EXPERIMENT;
      } else if (delta instanceof TasteProfileDelta.DiscardExperiment) {
        return TasteProfileDelta.Kind.DISCARD_EXPERIMENT;
      } else if (delta instanceof TasteProfileDelta.Archive) {
        return TasteProfileDelta.Kind.ARCHIVE;
      }
      return TasteProfileDelta.Kind.RE_PROMOTE;
    }

    private static List<String> appendStringDedup(List<String> list, String value) {
      if (list.contains(value)) {
        return list;
      }
      List<String> result = new ArrayList<>(list);
      result.add(value);
      return result;
    }

    private List<Object> appendObjectDedup(String fieldPath, List<Object> list, Object item) {
      String key = keyOf(fieldPath, item);
      if (list.stream().anyMatch(o -> keyOf(fieldPath, o).equals(key))) {
        // Dedup: an item with this key already exists → no-op merge (do not duplicate).
        return list;
      }
      List<Object> result = new ArrayList<>(list);
      result.add(item);
      return result;
    }

    // ---------------- document builder ----------------

    private DocBuilder withDoc(TasteProfileDocument doc) {
      return new DocBuilder(doc);
    }

    /**
     * Minimal copy-and-replace builder for the immutable {@link TasteProfileDocument}. Seeds every
     * field from the source; setters override individual sections. Keeps the per-op handlers free
     * of the 15-arg canonical constructor.
     */
    private static final class DocBuilder {
      private final TasteProfileDocument src;
      private FlavourPreferences flavour;
      private TexturePreferences texture;
      private IngredientPreferences ingredients;
      private CuisinePreferences cuisine;
      private CookingPreferences cooking;
      private List<RecipeRecommendation> recipesToRepeat;
      private List<RecipeRecommendation> recipesToAvoid;
      private List<ActiveExperiment> experiments;
      private List<String> insights;

      DocBuilder(TasteProfileDocument src) {
        this.src = src;
        this.flavour = src.flavourPreferences();
        this.texture = src.texturePreferences();
        this.ingredients = src.ingredientPreferences();
        this.cuisine = src.cuisinePreferences();
        this.cooking = src.cookingPreferences();
        this.recipesToRepeat = src.recipesToRepeat();
        this.recipesToAvoid = src.recipesToAvoid();
        this.experiments = src.activeExperiments();
        this.insights = src.learnedInsights();
      }

      DocBuilder flavour(FlavourPreferences v) {
        this.flavour = v;
        return this;
      }

      DocBuilder texture(TexturePreferences v) {
        this.texture = v;
        return this;
      }

      DocBuilder ingredients(IngredientPreferences v) {
        this.ingredients = v;
        return this;
      }

      DocBuilder cuisine(CuisinePreferences v) {
        this.cuisine = v;
        return this;
      }

      DocBuilder cooking(CookingPreferences v) {
        this.cooking = v;
        return this;
      }

      DocBuilder recipesToRepeat(List<RecipeRecommendation> v) {
        this.recipesToRepeat = v;
        return this;
      }

      DocBuilder recipesToAvoid(List<RecipeRecommendation> v) {
        this.recipesToAvoid = v;
        return this;
      }

      DocBuilder experiments(List<ActiveExperiment> v) {
        this.experiments = v;
        return this;
      }

      DocBuilder insights(List<String> v) {
        this.insights = v;
        return this;
      }

      TasteProfileDocument build() {
        return new TasteProfileDocument(
            src.lastUpdated(),
            src.version(),
            src.basedOnFeedbackCount(),
            src.feedbackCursor(),
            src.softConstraints(),
            flavour,
            texture,
            ingredients,
            cuisine,
            cooking,
            src.portionStyle(),
            src.householdContext(),
            recipesToRepeat,
            recipesToAvoid,
            experiments,
            insights);
      }
    }
  }
}
